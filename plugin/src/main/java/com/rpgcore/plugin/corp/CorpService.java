package com.rpgcore.plugin.corp;

import com.rpgcore.plugin.RpgCorePlugin;
import com.rpgcore.plugin.economy.MarketItem;
import com.rpgcore.plugin.economy.MarketService;
import com.rpgcore.plugin.util.DeferredSave;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Companies: the supply side of the economy, and something to own a piece of.
 *
 * A company turns three things into a fourth. Factories turn time into goods,
 * the market turns goods into gold, and the gold pays wages, upkeep and
 * dividends - so the same market that players buy from is now fed by
 * production somebody decided to build, rather than by a restock constant.
 *
 * The parts that matter:
 *
 *   <b>Supply chains.</b> A smelter needs iron and coal. It takes them from
 *   its own warehouse first, and buys the shortfall on the market if the
 *   company allows it. So an iron mine is worth building because somebody
 *   else's smelter will pay for what it digs, and a smelter with no iron
 *   quietly idles rather than inventing metal.
 *
 *   <b>Shares without an order book.</b> Investors always trade with the
 *   company itself, out of its treasury shares, at a price derived from what
 *   the company is worth. Buying is investment - the gold goes into the
 *   company and funds the next factory. Selling needs the company to have
 *   cash to buy back with, which is exactly the illiquidity a real small
 *   holding has.
 *
 *   <b>Takeovers.</b> Holders are UUIDs and a company id is a UUID, so a
 *   company holding another company's shares needs no special case. A tender
 *   offer pays every holder at once and moves the whole register.
 *
 * Everything runs on the main thread and is written by the shared save queue,
 * like every other store here.
 */
public final class CorpService {

    /** A pending invitation to join a company. */
    private record Invite(UUID company, long expiresAt) {
    }

    /** A standing offer for one company to buy another outright. */
    public record Offer(UUID id, UUID acquirer, UUID target, double premium,
                        long pricePerShare, long total, long expiresAt) {
    }

    private static final int PRICE_HISTORY = 24;

    private final RpgCorePlugin plugin;
    private final CorpConfig config;
    private final CorpStorage storage;
    private final DeferredSave writer;

    private final Map<UUID, Company> companies = new LinkedHashMap<>();
    /** Player -> the company that employs them. Rebuilt on load. */
    private final Map<UUID, UUID> employment = new HashMap<>();
    private final Map<UUID, Invite> invites = new HashMap<>();
    private final List<Offer> offers = new ArrayList<>();
    private boolean seeded;

    public CorpService(RpgCorePlugin plugin, CorpConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.storage = new CorpStorage(plugin);
        this.writer = new DeferredSave(plugin, CorpStorage.FILE, () -> storage.build(this));
    }

    public boolean enabled() {
        return plugin.rpgConfig().companyEnabled();
    }

    public CorpConfig config() {
        return config;
    }

    public void load() {
        companies.clear();
        employment.clear();
        offers.clear();
        storage.restore(this);
        // Any guilds this server had become companies before anything else
        // reads the register, and exactly once - the file is renamed after.
        GuildMigration.run(plugin, this, writer::flushBlocking);
        reindex();
        if (!seeded && config.seedEnabled()) {
            seedCompanies();
            seeded = true;
            save();
        }
        plugin.getLogger().info("Companies loaded: " + companies.size() + " ("
                + countNpc() + " seeded).");
    }

    private void reindex() {
        employment.clear();
        for (Company company : companies.values()) {
            for (UUID member : company.employees().keySet()) {
                employment.put(member, company.id());
            }
        }
    }

    public void save() {
        writer.markDirty();
    }

    public void saveNow() {
        writer.flushNow();
    }

    public Runnable pendingWrite() {
        return writer.pendingWrite();
    }

    /**
     * Writes now.
     *
     * Used wherever gold crosses between a player's balance and a company -
     * the same terms the bank and the auction house take, for the same reason:
     * the player's side lives on the scoreboard, which is written when the
     * world saves rather than when we ask.
     */
    private void commit() {
        writer.flushBlocking();
    }

    // ------------------------------------------------------------- seeding

    /**
     * Creates the companies the server starts with.
     *
     * Without them the exchange is empty on day one and nobody can invest in
     * anything until some player has built a factory - which they cannot
     * afford until they have earned gold in an economy that has no supply.
     * These break that circle: they produce from the first day, they pay
     * dividends, and they can be bought outright by anybody who saves enough.
     */
    private void seedCompanies() {
        for (CorpConfig.Seed seed : config.seeds()) {
            if (byName(seed.name()) != null) {
                continue;
            }
            Company company = new Company(UUID.randomUUID(), seed.name(),
                    uniqueTicker(seed.ticker()), null, true, System.currentTimeMillis());
            company.cash(seed.cash());
            company.sharesIssued(seed.shares());
            // Most of it is already owned - by the public, who will sell at a
            // price - and the rest is unissued capital the company can sell
            // to raise money. A company that held all its own shares would
            // belong to nobody, and be free to take.
            long float_ = Math.max(1, seed.shares() * 7 / 10);
            company.setHolding(Company.PUBLIC, float_);
            company.setHolding(company.id(), seed.shares() - float_);
            company.sellPercent(config.defaultSellPercent());
            company.autoBuyInputs(config.defaultAutoBuyInputs());
            company.dividendPercent(config.defaultDividendPercent());
            for (Map.Entry<String, Integer> entry : seed.factories().entrySet()) {
                if (config.factory(entry.getKey()) == null) {
                    plugin.getLogger().warning(CorpConfig.FILE + ": seed company " + seed.name()
                            + " wants unknown factory '" + entry.getKey() + "' - skipped.");
                    continue;
                }
                for (int i = 0; i < entry.getValue(); i++) {
                    company.factories().add(new Factory(entry.getKey(), 1));
                }
            }
            revalue(company);
            companies.put(company.id(), company);
        }
    }

    private String uniqueTicker(String wanted) {
        String base = wanted == null || wanted.isBlank() ? "CORP" : wanted.toUpperCase(Locale.ROOT);
        String candidate = base;
        int suffix = 2;
        while (byTicker(candidate) != null) {
            candidate = base.substring(0, Math.min(3, base.length())) + suffix++;
        }
        return candidate;
    }

    // ------------------------------------------------------------- lookups

    public Company byId(UUID id) {
        return id == null ? null : companies.get(id);
    }

    public Company byName(String name) {
        if (name == null) {
            return null;
        }
        for (Company company : companies.values()) {
            if (company.name().equalsIgnoreCase(name)) {
                return company;
            }
        }
        return null;
    }

    public Company byTicker(String ticker) {
        if (ticker == null) {
            return null;
        }
        for (Company company : companies.values()) {
            if (company.ticker().equalsIgnoreCase(ticker)) {
                return company;
            }
        }
        return null;
    }

    /** By name or ticker, which is what a player will actually type. */
    public Company find(String text) {
        Company byTicker = byTicker(text);
        return byTicker != null ? byTicker : byName(text);
    }

    public Company employerOf(UUID player) {
        return byId(employment.get(player));
    }

    public Company employerOf(Player player) {
        return employerOf(player.getUniqueId());
    }

    public List<Company> all() {
        return new ArrayList<>(companies.values());
    }

    public int count() {
        return companies.size();
    }

    public int countNpc() {
        int npc = 0;
        for (Company company : companies.values()) {
            if (company.npc()) {
                npc++;
            }
        }
        return npc;
    }

    /** Companies by value, which is the order an exchange screen wants. */
    public List<Company> listed() {
        List<Company> listed = new ArrayList<>(companies.values());
        listed.sort(Comparator.comparingDouble(this::marketCap).reversed());
        return listed;
    }

    /**
     * True when this player may spend the company's money - their own company
     * as CEO or director, or any company their own company controls.
     */
    public boolean manages(Player player, Company company) {
        if (company == null) {
            return false;
        }
        if (company.manages(player.getUniqueId())) {
            return true;
        }
        Company parent = employerOf(player);
        if (parent == null || !parent.manages(player.getUniqueId())) {
            return false;
        }
        UUID controller = company.controllingHolder();
        return controller != null && controller.equals(parent.id());
    }

    // ------------------------------------------------------------ valuation

    /** What the warehouse would fetch if it were all sold at today's bid. */
    public double warehouseValue(Company company) {
        double value = 0;
        for (Map.Entry<Material, Long> entry : company.warehouse().entrySet()) {
            MarketItem item = plugin.market().byMaterial(entry.getKey());
            if (item != null) {
                value += entry.getValue() * item.bid(plugin.economyConfig());
            }
        }
        return value;
    }

    /** Plants at what they would sell for, which is under what they cost. */
    public double factoryValue(Company company) {
        double value = 0;
        for (Factory factory : company.factories()) {
            CorpConfig.FactoryType type = config.factory(factory.typeId());
            if (type == null) {
                continue;
            }
            for (int level = 1; level <= factory.level(); level++) {
                value += config.upgradeCostAt(type, level - 1) * 0.7;
            }
        }
        return value;
    }

    public double bookValue(Company company) {
        return company.cash() + warehouseValue(company) + factoryValue(company);
    }

    /**
     * What the whole company is worth: what it owns, blended with what it
     * earns.
     *
     * Book value alone prices a company like scrap metal and ignores that it
     * is a going concern; earnings alone price a company that had one good
     * day as if it will have them forever. The blend is the usual compromise,
     * and the weight is in the config for operators who disagree.
     */
    public double fairValue(Company company) {
        double book = bookValue(company);
        double annualProfit = (double) company.lastProfit() * plugin.economyConfig().daysPerYear();
        double earnings = Math.max(0, annualProfit) * config.earningsMultiple();
        double value = book * config.bookWeight() + earnings * (1 - config.bookWeight());
        return Math.max(0, value);
    }

    /**
     * Value per share in issue.
     *
     * Divided by the outstanding count rather than everything ever issued.
     * That is what makes selling treasury shares value-neutral - the company
     * gains exactly the cash the new shares are worth - and it is what makes
     * buying every outstanding share cost exactly what the company is worth,
     * rather than a fraction of it.
     */
    public double fairPrice(Company company) {
        long outstanding = company.outstandingShares();
        if (outstanding <= 0) {
            return config.minSharePrice();
        }
        return Math.max(config.minSharePrice(), fairValue(company) / outstanding);
    }

    /** Recomputes the traded price from fair value and current sentiment. */
    public void revalue(Company company) {
        company.sharePrice(Math.max(config.minSharePrice(), fairPrice(company) * company.sentiment()));
    }

    public double askPrice(Company company) {
        return company.sharePrice() * (1 + config.shareSpreadPercent() / 200.0);
    }

    public double bidPrice(Company company) {
        return company.sharePrice() * (1 - config.shareSpreadPercent() / 200.0);
    }

    public double marketCap(Company company) {
        return company.sharePrice() * company.outstandingShares();
    }

    /** Dividend per share over price, annualised - what an investor compares. */
    public double dividendYieldPercent(Company company) {
        if (company.sharePrice() <= 0 || company.publicShares() <= 0) {
            return 0;
        }
        double perShare = company.lastDividends() / (double) company.publicShares();
        return perShare * plugin.economyConfig().daysPerYear() / company.sharePrice() * 100.0;
    }

    /** Price over annualised earnings per share. Negative earnings give 0. */
    public double priceEarnings(Company company) {
        double annual = (double) company.lastProfit() * plugin.economyConfig().daysPerYear();
        if (annual <= 0 || company.sharesIssued() <= 0) {
            return 0;
        }
        return company.sharePrice() / (annual / company.sharesIssued());
    }

    // ------------------------------------------------------------ lifecycle

    public boolean create(Player player, String name) {
        if (!guard(player)) {
            return false;
        }
        if (employerOf(player) != null) {
            player.sendMessage(ChatColor.RED + "[기업] 이미 다른 회사에 속해 있습니다. 먼저 나가야 합니다.");
            return false;
        }
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.length() > config.nameMaxLength()) {
            player.sendMessage(ChatColor.RED + "[기업] 이름은 1~"
                    + config.nameMaxLength() + "자여야 합니다.");
            return false;
        }
        if (byName(trimmed) != null) {
            player.sendMessage(ChatColor.RED + "[기업] 같은 이름의 회사가 이미 있습니다.");
            return false;
        }
        long cost = config.createCost();
        if (!plugin.economy().take(player, (int) Math.min(Integer.MAX_VALUE, cost))) {
            player.sendMessage(ChatColor.RED + "[기업] 창업 자본금 " + cost + " 골드가 부족합니다. (보유 "
                    + plugin.economy().balance(player) + ")");
            return false;
        }

        Company company = new Company(UUID.randomUUID(), trimmed,
                uniqueTicker(tickerFor(trimmed)), player.getUniqueId(), false,
                System.currentTimeMillis());
        // Most of the founding capital becomes working capital; the rest is
        // the registration tax and leaves circulation.
        long registration = config.createCostToTreasury();
        company.cash(cost - registration);
        if (registration > 0) {
            plugin.market().creditTreasury(registration);
            plugin.macro().collectFee(registration);
        }
        company.sharesIssued(config.founderShares());
        company.setHolding(player.getUniqueId(), config.founderShares());
        company.sellPercent(config.defaultSellPercent());
        company.autoBuyInputs(config.defaultAutoBuyInputs());
        company.dividendPercent(config.defaultDividendPercent());
        company.employees().put(player.getUniqueId(),
                new Company.Employee(player.getName(), Company.Role.CEO, 0, System.currentTimeMillis()));
        companies.put(company.id(), company);
        employment.put(player.getUniqueId(), company.id());
        revalue(company);
        commit();

        player.sendMessage(ChatColor.GREEN + "[기업] " + ChatColor.WHITE + trimmed
                + ChatColor.GREEN + " (" + company.ticker() + ") 를 설립했습니다.");
        player.sendMessage(ChatColor.GRAY + "  자본금 " + company.cash() + "골드 · 주식 "
                + company.sharesIssued() + "주 전량 보유 · 등록세 " + registration + "골드");
        player.sendMessage(ChatColor.GRAY + "  /company factory 로 공장을 지으세요. 공장이 있어야 매출이 생깁니다.");
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8F, 1.0F);
        plugin.getServer().broadcastMessage(ChatColor.DARK_GREEN + "[기업] " + ChatColor.WHITE
                + player.getName() + ChatColor.GRAY + " 님이 " + ChatColor.WHITE + trimmed
                + ChatColor.GRAY + " 을(를) 설립했습니다.");
        return true;
    }

    private static String tickerFor(String name) {
        StringBuilder out = new StringBuilder();
        for (char c : name.toUpperCase(Locale.ROOT).toCharArray()) {
            if (c >= 'A' && c <= 'Z') {
                out.append(c);
            }
            if (out.length() >= 4) {
                break;
            }
        }
        return out.length() >= 2 ? out.toString() : "CO" + Math.abs(name.hashCode() % 100);
    }

    public boolean disband(Player player) {
        Company company = employerOf(player);
        if (company == null || !player.getUniqueId().equals(company.ceo())) {
            player.sendMessage(ChatColor.RED + "[기업] 대표만 폐업할 수 있습니다.");
            return false;
        }
        if (company.publicShares() > company.sharesOf(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "[기업] 다른 주주가 있는 회사는 폐업할 수 없습니다. "
                    + "먼저 주식을 전부 되사거나 매각하세요.");
            return false;
        }
        liquidate(company, "대표가 폐업했습니다");
        player.sendMessage(ChatColor.YELLOW + "[기업] 회사를 정리했습니다. 남은 자산은 주주에게 배분되었습니다.");
        return true;
    }

    /**
     * Winds a company up: warehouse sold, plants sold for scrap, what is left
     * split among the people who owned it.
     *
     * Creditors first is the honest order, but the only creditor here is the
     * market itself and it has already been paid, so what remains after debts
     * is the shareholders'. A company that dies owing money simply has
     * nothing to distribute.
     */
    private void liquidate(Company company, String why) {
        for (Map.Entry<Material, Long> entry : new ArrayList<>(company.warehouse().entrySet())) {
            MarketItem item = plugin.market().byMaterial(entry.getKey());
            if (item == null) {
                continue;
            }
            MarketService.Fill fill = plugin.market().sellFor(item, entry.getValue());
            company.addCash(fill.gold());
        }
        company.warehouse().clear();
        company.addCash(Math.round(factoryValue(company)));
        company.factories().clear();

        long distributable = Math.max(0, company.cash());
        long publicShares = company.publicShares();
        if (distributable > 0 && publicShares > 0) {
            for (Map.Entry<UUID, Long> entry : new ArrayList<>(company.holders().entrySet())) {
                if (entry.getKey().equals(company.id())) {
                    continue;
                }
                long share = distributable * entry.getValue() / publicShares;
                if (share <= 0) {
                    continue;
                }
                payHolder(entry.getKey(), share, company.name() + " 청산 배분");
            }
        }
        for (UUID member : company.employees().keySet()) {
            employment.remove(member);
            Player online = plugin.getServer().getPlayer(member);
            if (online != null) {
                online.sendMessage(ChatColor.RED + "[기업] " + company.name() + " 이(가) 정리되었습니다. ("
                        + why + ")");
            }
        }
        offers.removeIf(offer -> offer.target().equals(company.id())
                || offer.acquirer().equals(company.id()));
        // Shares of a dissolved company are worthless; holdings elsewhere are
        // untouched because the register lives on the other company.
        companies.remove(company.id());
        commit();
        plugin.getServer().broadcastMessage(ChatColor.DARK_RED + "[기업] " + ChatColor.WHITE
                + company.name() + ChatColor.GRAY + " 이(가) 문을 닫았습니다. (" + why + ")");
    }

    /** Pays gold to a holder: a player, another company, or the public. */
    private void payHolder(UUID holder, long amount, String note) {
        if (amount <= 0) {
            return;
        }
        if (holder.equals(Company.PUBLIC)) {
            // Paid to nobody in particular, so it leaves circulation through
            // the national treasury rather than being conjured away.
            plugin.market().creditTreasury(amount);
            return;
        }
        Company corporate = byId(holder);
        if (corporate != null) {
            corporate.addCash(amount);
            return;
        }
        plugin.mailbox().giveGold(holder, (int) Math.min(Integer.MAX_VALUE, amount), note);
    }

    // --------------------------------------------------------------- people

    public boolean invite(Player ceo, Player target) {
        Company company = employerOf(ceo);
        if (company == null || !company.manages(ceo.getUniqueId())) {
            ceo.sendMessage(ChatColor.RED + "[기업] 대표나 임원만 채용할 수 있습니다.");
            return false;
        }
        if (employerOf(target) != null) {
            ceo.sendMessage(ChatColor.RED + "[기업] 그 사람은 이미 다른 회사에 속해 있습니다.");
            return false;
        }
        if (company.headcount() >= config.maxEmployees()) {
            ceo.sendMessage(ChatColor.RED + "[기업] 정원(" + config.maxEmployees() + "명)이 찼습니다.");
            return false;
        }
        invites.put(target.getUniqueId(), new Invite(company.id(),
                System.currentTimeMillis() + config.inviteTimeoutSeconds() * 1000L));
        ceo.sendMessage(ChatColor.GREEN + "[기업] " + target.getName() + " 님에게 입사를 제안했습니다.");
        target.sendMessage(ChatColor.GREEN + "[기업] " + ChatColor.WHITE + company.name()
                + ChatColor.GREEN + " 에서 입사 제안이 왔습니다. " + ChatColor.YELLOW
                + "/company accept" + ChatColor.GREEN + " 로 수락 ("
                + config.inviteTimeoutSeconds() + "초 안에)");
        target.sendMessage(ChatColor.GRAY + "  일당 " + config.wagePerEmployee()
                + "골드가 매 경제일마다 나옵니다. 회사가 망하면 못 받습니다.");
        return true;
    }

    public boolean accept(Player player) {
        Invite invite = invites.remove(player.getUniqueId());
        if (invite == null || invite.expiresAt() < System.currentTimeMillis()) {
            player.sendMessage(ChatColor.RED + "[기업] 유효한 입사 제안이 없습니다.");
            return false;
        }
        Company company = byId(invite.company());
        if (company == null) {
            player.sendMessage(ChatColor.RED + "[기업] 그 회사는 이제 없습니다.");
            return false;
        }
        if (employerOf(player) != null) {
            player.sendMessage(ChatColor.RED + "[기업] 이미 다른 회사에 속해 있습니다.");
            return false;
        }
        if (company.headcount() >= config.maxEmployees()) {
            player.sendMessage(ChatColor.RED + "[기업] 그 회사는 정원이 찼습니다.");
            return false;
        }
        company.employees().put(player.getUniqueId(),
                new Company.Employee(player.getName(), Company.Role.STAFF, 0, System.currentTimeMillis()));
        employment.put(player.getUniqueId(), company.id());
        save();
        player.sendMessage(ChatColor.GREEN + "[기업] " + company.name() + " 에 입사했습니다.");
        announce(company, ChatColor.GREEN + "[기업] " + player.getName() + " 님이 입사했습니다.");
        return true;
    }

    public boolean leave(Player player) {
        Company company = employerOf(player);
        if (company == null) {
            player.sendMessage(ChatColor.RED + "[기업] 속한 회사가 없습니다.");
            return false;
        }
        if (player.getUniqueId().equals(company.ceo())) {
            player.sendMessage(ChatColor.RED + "[기업] 대표는 먼저 /company transfer 로 대표직을 넘기거나 "
                    + "/company disband 로 폐업해야 합니다.");
            return false;
        }
        company.employees().remove(player.getUniqueId());
        employment.remove(player.getUniqueId());
        save();
        player.sendMessage(ChatColor.YELLOW + "[기업] " + company.name() + " 에서 퇴사했습니다.");
        announce(company, ChatColor.YELLOW + "[기업] " + player.getName() + " 님이 퇴사했습니다.");
        return true;
    }

    public boolean fire(Player ceo, String targetName) {
        Company company = employerOf(ceo);
        if (company == null || !ceo.getUniqueId().equals(company.ceo())) {
            ceo.sendMessage(ChatColor.RED + "[기업] 대표만 해고할 수 있습니다.");
            return false;
        }
        for (Map.Entry<UUID, Company.Employee> entry : company.employees().entrySet()) {
            if (!entry.getValue().name().equalsIgnoreCase(targetName)) {
                continue;
            }
            if (entry.getKey().equals(company.ceo())) {
                ceo.sendMessage(ChatColor.RED + "[기업] 대표는 해고할 수 없습니다.");
                return false;
            }
            company.employees().remove(entry.getKey());
            employment.remove(entry.getKey());
            save();
            ceo.sendMessage(ChatColor.YELLOW + "[기업] " + entry.getValue().name() + " 님을 해고했습니다.");
            Player online = plugin.getServer().getPlayer(entry.getKey());
            if (online != null) {
                online.sendMessage(ChatColor.RED + "[기업] " + company.name() + " 에서 해고되었습니다.");
            }
            return true;
        }
        ceo.sendMessage(ChatColor.RED + "[기업] 그런 직원이 없습니다: " + targetName);
        return false;
    }

    public boolean setRole(Player ceo, String targetName, Company.Role role) {
        Company company = employerOf(ceo);
        if (company == null || !ceo.getUniqueId().equals(company.ceo())) {
            ceo.sendMessage(ChatColor.RED + "[기업] 대표만 직급을 바꿀 수 있습니다.");
            return false;
        }
        for (Map.Entry<UUID, Company.Employee> entry : company.employees().entrySet()) {
            if (entry.getValue().name().equalsIgnoreCase(targetName)) {
                if (entry.getKey().equals(company.ceo())) {
                    ceo.sendMessage(ChatColor.RED + "[기업] 대표의 직급은 바꿀 수 없습니다.");
                    return false;
                }
                entry.getValue().role(role);
                save();
                ceo.sendMessage(ChatColor.GREEN + "[기업] " + entry.getValue().name() + " → "
                        + role.label());
                return true;
            }
        }
        ceo.sendMessage(ChatColor.RED + "[기업] 그런 직원이 없습니다: " + targetName);
        return false;
    }

    public boolean transfer(Player ceo, String targetName) {
        Company company = employerOf(ceo);
        if (company == null || !ceo.getUniqueId().equals(company.ceo())) {
            ceo.sendMessage(ChatColor.RED + "[기업] 대표만 대표직을 넘길 수 있습니다.");
            return false;
        }
        for (Map.Entry<UUID, Company.Employee> entry : company.employees().entrySet()) {
            if (entry.getValue().name().equalsIgnoreCase(targetName)
                    && !entry.getKey().equals(company.ceo())) {
                company.employee(company.ceo()).role(Company.Role.DIRECTOR);
                company.ceo(entry.getKey());
                entry.getValue().role(Company.Role.CEO);
                save();
                announce(company, ChatColor.GREEN + "[기업] 대표가 " + entry.getValue().name()
                        + " 님으로 바뀌었습니다.");
                return true;
            }
        }
        ceo.sendMessage(ChatColor.RED + "[기업] 그런 직원이 없습니다: " + targetName);
        return false;
    }

    private void announce(Company company, String message) {
        for (UUID member : company.employees().keySet()) {
            Player online = plugin.getServer().getPlayer(member);
            if (online != null) {
                online.sendMessage(message);
            }
        }
    }

    // ---------------------------------------------------------------- money

    public boolean deposit(Player player, long amount) {
        Company company = employerOf(player);
        if (company == null) {
            player.sendMessage(ChatColor.RED + "[기업] 속한 회사가 없습니다.");
            return false;
        }
        if (amount <= 0) {
            player.sendMessage(ChatColor.RED + "[기업] 1 이상을 넣어야 합니다.");
            return false;
        }
        if (!plugin.economy().take(player, (int) Math.min(Integer.MAX_VALUE, amount))) {
            player.sendMessage(ChatColor.RED + "[기업] 골드가 부족합니다.");
            return false;
        }
        company.addCash(amount);
        commit();
        player.sendMessage(ChatColor.GREEN + "[기업] 회사 자금에 " + amount + " 골드를 넣었습니다. (현금 "
                + company.cash() + ")");
        return true;
    }

    public boolean withdraw(Player player, long amount) {
        Company company = employerOf(player);
        if (company == null || !company.manages(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "[기업] 대표나 임원만 자금을 꺼낼 수 있습니다.");
            return false;
        }
        if (amount <= 0 || amount > company.cash()) {
            player.sendMessage(ChatColor.RED + "[기업] 회사 현금이 부족합니다. (현금 " + company.cash() + ")");
            return false;
        }
        company.addCash(-amount);
        commit();
        plugin.economy().refund(player, (int) Math.min(Integer.MAX_VALUE, amount));
        player.sendMessage(ChatColor.GREEN + "[기업] " + amount + " 골드를 꺼냈습니다. (회사 현금 "
                + company.cash() + ")");
        return true;
    }

    // ------------------------------------------------------------ factories

    public boolean buildFactory(Player player, String typeId) {
        Company company = employerOf(player);
        if (company == null || !company.manages(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "[기업] 대표나 임원만 공장을 지을 수 있습니다.");
            return false;
        }
        CorpConfig.FactoryType type = config.factory(typeId);
        if (type == null) {
            player.sendMessage(ChatColor.RED + "[기업] 그런 공장이 없습니다: " + typeId);
            return false;
        }
        if (company.cash() < type.buildCost()) {
            player.sendMessage(ChatColor.RED + "[기업] 건설비 " + type.buildCost()
                    + " 골드가 부족합니다. (현금 " + company.cash() + ")");
            return false;
        }
        company.addCash(-type.buildCost());
        company.factories().add(new Factory(type.id(), 1));
        revalue(company);
        commit();
        player.sendMessage(ChatColor.GREEN + "[기업] " + type.name() + " 을(를) 지었습니다. 하루 "
                + Math.round(config.outputAt(type, 1)) + "개 생산 · 유지비 "
                + config.upkeepAt(type, 1) + "골드");
        return true;
    }

    public boolean upgradeFactory(Player player, int index) {
        Company company = employerOf(player);
        if (company == null || !company.manages(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "[기업] 대표나 임원만 공장을 올릴 수 있습니다.");
            return false;
        }
        if (index < 0 || index >= company.factories().size()) {
            player.sendMessage(ChatColor.RED + "[기업] 그런 공장이 없습니다.");
            return false;
        }
        Factory factory = company.factories().get(index);
        CorpConfig.FactoryType type = config.factory(factory.typeId());
        if (type == null) {
            player.sendMessage(ChatColor.RED + "[기업] 설비 정보를 찾을 수 없습니다.");
            return false;
        }
        if (factory.level() >= config.maxLevel()) {
            player.sendMessage(ChatColor.RED + "[기업] 이미 최고 등급(" + config.maxLevel() + ")입니다.");
            return false;
        }
        long cost = config.upgradeCostAt(type, factory.level());
        if (company.cash() < cost) {
            player.sendMessage(ChatColor.RED + "[기업] 증설비 " + cost + " 골드가 부족합니다. (현금 "
                    + company.cash() + ")");
            return false;
        }
        company.addCash(-cost);
        factory.level(factory.level() + 1);
        revalue(company);
        commit();
        player.sendMessage(ChatColor.GREEN + "[기업] " + type.name() + " Lv." + factory.level()
                + " · 하루 " + Math.round(config.outputAt(type, factory.level()))
                + "개 · 유지비 " + config.upkeepAt(type, factory.level()) + "골드");
        return true;
    }

    public boolean sellFactory(Player player, int index) {
        Company company = employerOf(player);
        if (company == null || !company.manages(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "[기업] 대표나 임원만 공장을 팔 수 있습니다.");
            return false;
        }
        if (index < 0 || index >= company.factories().size()) {
            player.sendMessage(ChatColor.RED + "[기업] 그런 공장이 없습니다.");
            return false;
        }
        Factory factory = company.factories().get(index);
        CorpConfig.FactoryType type = config.factory(factory.typeId());
        long refund = 0;
        if (type != null) {
            for (int level = 1; level <= factory.level(); level++) {
                refund += Math.round(config.upgradeCostAt(type, level - 1) * 0.7);
            }
        }
        company.factories().remove(index);
        company.addCash(refund);
        revalue(company);
        commit();
        player.sendMessage(ChatColor.YELLOW + "[기업] 공장을 매각해 " + refund + " 골드를 회수했습니다. "
                + ChatColor.GRAY + "(건설비의 70%)");
        return true;
    }

    // ------------------------------------------------------------ warehouse

    /** An employee sells what they are carrying into the company warehouse. */
    public boolean supply(Player player, boolean all) {
        Company company = employerOf(player);
        if (company == null) {
            player.sendMessage(ChatColor.RED + "[기업] 속한 회사가 없습니다.");
            return false;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held == null || held.getType().isAir()) {
            player.sendMessage(ChatColor.RED + "[기업] 손에 든 것이 없습니다.");
            return false;
        }
        MarketItem item = plugin.market().byMaterial(held.getType());
        if (item == null) {
            player.sendMessage(ChatColor.RED + "[기업] 시장에서 값이 매겨지지 않는 품목입니다.");
            return false;
        }
        int units = all
                ? plugin.market().countPlain(player, held.getType())
                : held.getAmount();
        if (units <= 0) {
            player.sendMessage(ChatColor.RED + "[기업] 넘길 수 있는 물건이 없습니다.");
            return false;
        }
        // Paid at the market's bid, out of company cash: the company is
        // buying stock it would otherwise have to buy from the market anyway.
        long price = Math.round(item.bid(plugin.economyConfig()) * units);
        if (company.cash() < price) {
            player.sendMessage(ChatColor.RED + "[기업] 회사 현금이 부족합니다. 필요 " + price
                    + ", 현금 " + company.cash() + ".");
            return false;
        }
        long room = config.warehouseCap() - company.stockOf(held.getType());
        if (room < units) {
            player.sendMessage(ChatColor.RED + "[기업] 창고가 가득 찼습니다. (최대 "
                    + config.warehouseCap() + ")");
            return false;
        }
        if (!plugin.market().removePlainFor(player, held.getType(), units)) {
            player.sendMessage(ChatColor.RED + "[기업] 물건을 꺼내지 못했습니다.");
            return false;
        }
        company.addStock(held.getType(), units);
        company.addCash(-price);
        commit();
        plugin.economy().refund(player, (int) Math.min(Integer.MAX_VALUE, price));
        player.sendMessage(ChatColor.GREEN + "[기업] " + held.getType().getKey().getKey() + " "
                + units + "개를 회사에 납품하고 " + price + " 골드를 받았습니다.");
        return true;
    }

    /** Sells part of the warehouse to the market right now. */
    public boolean sellStock(Player player, Material material, long units) {
        Company company = employerOf(player);
        if (company == null || !company.manages(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "[기업] 대표나 임원만 출고할 수 있습니다.");
            return false;
        }
        long have = company.stockOf(material);
        if (have <= 0) {
            player.sendMessage(ChatColor.RED + "[기업] 창고에 그 물건이 없습니다.");
            return false;
        }
        MarketItem item = plugin.market().byMaterial(material);
        if (item == null) {
            player.sendMessage(ChatColor.RED + "[기업] 시장에서 취급하지 않는 품목입니다.");
            return false;
        }
        MarketService.Fill fill = plugin.market().sellFor(item, Math.min(units, have));
        if (fill.units() <= 0) {
            player.sendMessage(ChatColor.RED + "[기업] 시장이 지금 이 물건을 받지 못합니다. "
                    + "(금고가 비었거나 재고가 넘칩니다)");
            return false;
        }
        company.addStock(material, -fill.units());
        company.addCash(fill.gold());
        commit();
        player.sendMessage(ChatColor.GREEN + "[기업] " + material.getKey().getKey() + " "
                + fill.units() + "개를 팔아 " + fill.gold() + " 골드를 벌었습니다.");
        return true;
    }

    // --------------------------------------------------------------- shares

    /**
     * Buys shares out of the company's treasury.
     *
     * This is investment in the literal sense: the gold goes into the company
     * and pays for its next factory. It is not a bet between two players -
     * there is no seller on the other side, which is exactly why no order
     * book is needed and why a company with nothing left to sell has to issue
     * new shares (and dilute) before it can raise more.
     */
    public boolean buyShares(Player player, Company company, long units) {
        if (!guard(player) || company == null) {
            return false;
        }
        if (units <= 0) {
            player.sendMessage(ChatColor.RED + "[주식] 1주 이상 사야 합니다.");
            return false;
        }
        long available = company.treasuryShares();
        if (available < units && company.npc() && config.npcAutoIssue()) {
            // A seeded company is public: it prints the paper rather than
            // turning an investor away.
            company.issue(units - available);
            available = company.treasuryShares();
        }
        long want = Math.min(units, available);
        if (want <= 0) {
            player.sendMessage(ChatColor.RED + "[주식] " + company.name()
                    + " 은(는) 지금 팔 수 있는 주식이 없습니다. "
                    + ChatColor.GRAY + "(대표가 /company issue 로 신주를 발행해야 합니다)");
            return false;
        }
        long gross = Math.max(1, Math.round(askPrice(company) * want));
        long tax = Math.round(gross * config.shareTaxPercent() / 100.0);
        long total = gross + tax;
        if (total > Integer.MAX_VALUE) {
            player.sendMessage(ChatColor.RED + "[주식] 한 번에 사기에는 너무 큽니다. 수량을 줄이세요.");
            return false;
        }
        if (!plugin.economy().take(player, (int) total)) {
            player.sendMessage(ChatColor.RED + "[주식] 골드가 부족합니다. 필요 " + total
                    + ", 보유 " + plugin.economy().balance(player) + ".");
            return false;
        }

        company.addCash(gross);
        company.moveShares(company.id(), player.getUniqueId(), want);
        applyTradeImpact(company, want, true);
        plugin.market().creditTreasury(tax);
        plugin.macro().collectFee(tax);
        plugin.macro().recordTrade(gross);
        commit();

        player.sendMessage(ChatColor.GREEN + "[주식] " + company.ticker() + " " + want
                + "주를 " + ChatColor.GOLD + total + plugin.rpgConfig().goldSymbol()
                + ChatColor.GREEN + " 에 샀습니다. " + ChatColor.GRAY + "(주당 "
                + MarketService.money(gross / (double) want) + ", 세금 " + tax + ")");
        player.sendMessage(ChatColor.GRAY + "  보유 " + company.sharesOf(player.getUniqueId())
                + "주 · 지분 " + String.format(Locale.ROOT, "%.1f%%",
                company.sharesOf(player.getUniqueId()) * 100.0 / Math.max(1, company.sharesIssued())));
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.7F, 1.6F);
        if (want < units) {
            player.sendMessage(ChatColor.GRAY + "  " + (units - want) + "주는 물량이 없어 사지 못했습니다.");
        }
        return true;
    }

    /**
     * Sells shares back to the company.
     *
     * The company is the only buyer, and it pays out of its own cash - capped
     * per day so one holder cashing out cannot empty the till that pays
     * everybody's wages. A holder who cannot sell today is not being cheated;
     * they own a piece of a business that is short of cash, and that is the
     * risk they took.
     */
    public boolean sellShares(Player player, Company company, long units) {
        if (!guard(player) || company == null) {
            return false;
        }
        long held = company.sharesOf(player.getUniqueId());
        long want = Math.min(units, held);
        if (want <= 0) {
            player.sendMessage(ChatColor.RED + "[주식] 가진 주식이 없습니다.");
            return false;
        }
        long allowance = Math.max(0,
                Math.round(company.cash() * config.buybackCashPercent() / 100.0)
                        - company.buybackSpentToday());
        long budget = Math.max(0, Math.min(company.cash(), allowance));
        double unit = bidPrice(company);
        long affordable = unit <= 0 ? want : (long) Math.floor(budget / unit);
        if (affordable <= 0) {
            player.sendMessage(ChatColor.RED + "[주식] " + company.name()
                    + " 이(가) 오늘 되사 줄 현금이 없습니다. " + ChatColor.GRAY
                    + "(하루 매입 한도는 현금의 " + (int) config.buybackCashPercent() + "% 입니다)");
            return false;
        }
        long sold = Math.min(want, affordable);
        long gross = Math.max(1, Math.round(unit * sold));
        long tax = Math.round(gross * config.shareTaxPercent() / 100.0);
        long net = Math.max(0, gross - tax);

        company.addCash(-gross);
        company.buybackSpentToday(company.buybackSpentToday() + gross);
        company.moveShares(player.getUniqueId(), company.id(), sold);
        applyTradeImpact(company, sold, false);
        plugin.market().creditTreasury(tax);
        plugin.macro().collectFee(tax);
        plugin.macro().recordTrade(gross);
        commit();
        plugin.economy().refund(player, (int) Math.min(Integer.MAX_VALUE, net));

        player.sendMessage(ChatColor.GREEN + "[주식] " + company.ticker() + " " + sold
                + "주를 " + ChatColor.GOLD + net + plugin.rpgConfig().goldSymbol()
                + ChatColor.GREEN + " 에 팔았습니다. " + ChatColor.GRAY + "(주당 "
                + MarketService.money(gross / (double) sold) + ", 세금 " + tax + ")");
        if (sold < want) {
            player.sendMessage(ChatColor.GRAY + "  " + (want - sold)
                    + "주는 회사 현금이 모자라 오늘은 팔지 못했습니다.");
        }
        return true;
    }

    /**
     * Moves the price the way an order moves any price here: buying lifts it,
     * selling drops it, in proportion to how much of the company changed
     * hands. It decays back towards fair value every day.
     */
    private void applyTradeImpact(Company company, long units, boolean buying) {
        if (company.sharesIssued() <= 0) {
            return;
        }
        double fraction = units / (double) Math.max(1, company.outstandingShares());
        double move = config.tradeImpactPercent() / 100.0 * (fraction / 0.01);
        company.sentiment(company.sentiment() * (buying ? 1 + move : 1 - move));
        revalue(company);
    }

    /** New shares into the treasury, for a company that wants to raise money. */
    public boolean issueShares(Player player, long units) {
        Company company = employerOf(player);
        if (company == null || !player.getUniqueId().equals(company.ceo())) {
            player.sendMessage(ChatColor.RED + "[주식] 대표만 신주를 발행할 수 있습니다.");
            return false;
        }
        if (units <= 0 || units > company.sharesIssued() * 2L) {
            player.sendMessage(ChatColor.RED + "[주식] 한 번에 발행할 수 있는 신주는 기존 발행량의 "
                    + "2배까지입니다. (현재 " + company.sharesIssued() + "주)");
            return false;
        }
        double before = fairPrice(company);
        company.issue(units);
        revalue(company);
        commit();
        player.sendMessage(ChatColor.GREEN + "[주식] 신주 " + units + "주를 발행했습니다. 총 "
                + company.sharesIssued() + "주.");
        player.sendMessage(ChatColor.GRAY + "  주당 가치가 " + MarketService.money(before) + " → "
                + MarketService.money(fairPrice(company)) + " 로 희석되었습니다. "
                + "투자자가 사 가면 그 돈이 회사 현금이 됩니다.");
        return true;
    }

    /** Everything this holder owns, for a portfolio screen. */
    public Map<Company, Long> portfolioOf(UUID holder) {
        Map<Company, Long> out = new LinkedHashMap<>();
        for (Company company : listed()) {
            long shares = company.sharesOf(holder);
            if (shares > 0) {
                out.put(company, shares);
            }
        }
        return out;
    }

    // ------------------------------------------------------------ takeovers

    /**
     * Offers to buy every share of another company that the acquirer does not
     * already hold, at a premium to the market price.
     *
     * A seeded company has nobody to say no, so the offer settles at once. A
     * company somebody runs has to accept - unless the acquirer already holds
     * a majority, in which case they are only buying out a minority who
     * cannot outvote them anyway.
     */
    public boolean offerTakeover(Player player, Company target, double premiumPercent) {
        Company acquirer = employerOf(player);
        if (acquirer == null || !player.getUniqueId().equals(acquirer.ceo())) {
            player.sendMessage(ChatColor.RED + "[인수] 대표만 인수를 제안할 수 있습니다.");
            return false;
        }
        if (target == null || target.id().equals(acquirer.id())) {
            player.sendMessage(ChatColor.RED + "[인수] 대상 회사를 찾을 수 없습니다.");
            return false;
        }
        double premium = Math.clamp(premiumPercent,
                config.minTakeoverPremium(), config.maxTakeoverPremium());
        long unitPrice = Math.max(1, Math.round(target.sharePrice() * (1 + premium / 100.0)));
        long buying = sharesToAcquire(acquirer, target);
        if (buying <= 0) {
            player.sendMessage(ChatColor.YELLOW + "[인수] 이미 전부 보유하고 있습니다. "
                    + "/company absorb " + target.ticker() + " 로 합병할 수 있습니다.");
            return false;
        }
        long total = unitPrice * buying;
        if (acquirer.cash() < total) {
            player.sendMessage(ChatColor.RED + "[인수] 회사 현금이 부족합니다. 필요 " + total
                    + ", 현금 " + acquirer.cash() + ".");
            player.sendMessage(ChatColor.GRAY + "  " + buying + "주 x 주당 " + unitPrice
                    + " (시가 " + MarketService.money(target.sharePrice()) + " + 프리미엄 "
                    + (int) premium + "%)");
            return false;
        }

        boolean forced = target.npc()
                || acquirer.id().equals(target.controllingHolder());
        if (forced) {
            executeTakeover(acquirer, target, unitPrice);
            return true;
        }
        offers.removeIf(offer -> offer.acquirer().equals(acquirer.id())
                && offer.target().equals(target.id()));
        offers.add(new Offer(UUID.randomUUID(), acquirer.id(), target.id(), premium,
                unitPrice, total, System.currentTimeMillis() + config.offerTimeoutSeconds() * 1000L));
        save();
        player.sendMessage(ChatColor.GREEN + "[인수] " + target.name() + " 에 주당 " + unitPrice
                + " 골드(총 " + total + ")로 인수를 제안했습니다. 상대 대표의 수락을 기다립니다.");
        Player targetCeo = target.ceo() == null ? null : plugin.getServer().getPlayer(target.ceo());
        if (targetCeo != null) {
            targetCeo.sendMessage(ChatColor.GOLD + "[인수] " + ChatColor.WHITE + acquirer.name()
                    + ChatColor.GOLD + " 이(가) 우리 회사를 주당 " + unitPrice + " 골드에 인수하겠다고 합니다.");
            targetCeo.sendMessage(ChatColor.GRAY + "  시가 " + MarketService.money(target.sharePrice())
                    + " 대비 +" + (int) premium + "% · 총 " + total + "골드 · "
                    + ChatColor.YELLOW + "/company offers" + ChatColor.GRAY + " 에서 수락/거절");
        }
        return true;
    }

    /**
     * Shares the acquirer still has to buy: every outstanding share that is
     * not already theirs. The public's float counts - that is the point.
     */
    private long sharesToAcquire(Company acquirer, Company target) {
        return Math.max(0, target.outstandingShares() - target.sharesOf(acquirer.id()));
    }

    public List<Offer> offersFor(Company target) {
        List<Offer> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Offer offer : offers) {
            if (offer.target().equals(target.id()) && offer.expiresAt() > now) {
                out.add(offer);
            }
        }
        return out;
    }

    public boolean respondToOffer(Player player, int index, boolean accept) {
        Company target = employerOf(player);
        if (target == null || !player.getUniqueId().equals(target.ceo())) {
            player.sendMessage(ChatColor.RED + "[인수] 대표만 답할 수 있습니다.");
            return false;
        }
        List<Offer> pending = offersFor(target);
        if (index < 0 || index >= pending.size()) {
            player.sendMessage(ChatColor.RED + "[인수] 그런 제안이 없습니다.");
            return false;
        }
        Offer offer = pending.get(index);
        offers.remove(offer);
        Company acquirer = byId(offer.acquirer());
        if (acquirer == null) {
            player.sendMessage(ChatColor.RED + "[인수] 제안한 회사가 이제 없습니다.");
            save();
            return false;
        }
        if (!accept) {
            player.sendMessage(ChatColor.YELLOW + "[인수] 제안을 거절했습니다.");
            Player acquirerCeo = acquirer.ceo() == null ? null
                    : plugin.getServer().getPlayer(acquirer.ceo());
            if (acquirerCeo != null) {
                acquirerCeo.sendMessage(ChatColor.RED + "[인수] " + target.name()
                        + " 이(가) 제안을 거절했습니다.");
            }
            save();
            return true;
        }
        if (acquirer.cash() < offer.total()) {
            player.sendMessage(ChatColor.RED + "[인수] 상대 회사의 현금이 그새 모자라졌습니다. 제안이 취소됩니다.");
            save();
            return false;
        }
        executeTakeover(acquirer, target, offer.pricePerShare());
        return true;
    }

    /**
     * Pays every outside holder and moves the register.
     *
     * Treasury shares transfer without payment - the target would otherwise
     * be paying itself with money the acquirer is about to own anyway, which
     * is a circle that only inflates the price of a takeover.
     */
    private void executeTakeover(Company acquirer, Company target, long unitPrice) {
        long paid = 0;
        for (Map.Entry<UUID, Long> entry : new ArrayList<>(target.holders().entrySet())) {
            UUID holder = entry.getKey();
            long shares = entry.getValue();
            if (holder.equals(acquirer.id()) || shares <= 0) {
                continue;
            }
            if (!holder.equals(target.id())) {
                long payout = unitPrice * shares;
                paid += payout;
                payHolder(holder, payout, target.name() + " 인수 대금");
                Player online = plugin.getServer().getPlayer(holder);
                if (online != null) {
                    online.sendMessage(ChatColor.GOLD + "[인수] " + target.name() + " 주식 " + shares
                            + "주가 " + acquirer.name() + " 에 " + payout + " 골드로 인수되었습니다.");
                }
            }
            target.setHolding(holder, 0);
            target.holders().merge(acquirer.id(), shares, Long::sum);
        }
        acquirer.addCash(-paid);
        offers.removeIf(offer -> offer.target().equals(target.id()));
        revalue(target);
        revalue(acquirer);
        commit();
        plugin.getServer().broadcastMessage(ChatColor.GOLD + "[인수] " + ChatColor.WHITE
                + acquirer.name() + ChatColor.GRAY + " 이(가) " + ChatColor.WHITE + target.name()
                + ChatColor.GRAY + " 을(를) 인수했습니다. (주당 " + unitPrice + "골드, 총 " + paid + ")");
    }

    /** Merges a wholly owned subsidiary into its parent. */
    public boolean absorb(Player player, Company target) {
        Company acquirer = employerOf(player);
        if (acquirer == null || !player.getUniqueId().equals(acquirer.ceo())) {
            player.sendMessage(ChatColor.RED + "[인수] 대표만 합병할 수 있습니다.");
            return false;
        }
        if (target == null || target.id().equals(acquirer.id())) {
            player.sendMessage(ChatColor.RED + "[인수] 대상 회사를 찾을 수 없습니다.");
            return false;
        }
        if (sharesToAcquire(acquirer, target) > 0) {
            player.sendMessage(ChatColor.RED + "[인수] 지분을 100% 확보해야 합병할 수 있습니다. "
                    + "남은 주식 " + sharesToAcquire(acquirer, target) + "주.");
            return false;
        }
        acquirer.addCash(target.cash());
        for (Map.Entry<Material, Long> entry : target.warehouse().entrySet()) {
            acquirer.addStock(entry.getKey(), entry.getValue());
        }
        acquirer.factories().addAll(target.factories());
        for (Map.Entry<UUID, Company.Employee> entry : target.employees().entrySet()) {
            if (acquirer.headcount() >= config.maxEmployees() || acquirer.employs(entry.getKey())) {
                employment.remove(entry.getKey());
                continue;
            }
            entry.getValue().role(Company.Role.STAFF);
            acquirer.employees().put(entry.getKey(), entry.getValue());
            employment.put(entry.getKey(), acquirer.id());
        }
        companies.remove(target.id());
        offers.removeIf(offer -> offer.target().equals(target.id())
                || offer.acquirer().equals(target.id()));
        revalue(acquirer);
        commit();
        plugin.getServer().broadcastMessage(ChatColor.GOLD + "[합병] " + ChatColor.WHITE
                + target.name() + ChatColor.GRAY + " 이(가) " + ChatColor.WHITE + acquirer.name()
                + ChatColor.GRAY + " 에 흡수되었습니다.");
        return true;
    }

    // ------------------------------------------------------------ daily pass

    /**
     * One economic day of business, for every company in turn.
     *
     * The order inside a company is the order the money actually moves:
     * produce, sell, pay the staff, count what is left, hand some of it to
     * the owners, then re-price the shares against what all of that did.
     */
    public void dailyClose(int day) {
        expireOffers();
        List<Company> doomed = new ArrayList<>();
        for (Company company : new ArrayList<>(companies.values())) {
            long revenue = 0;
            long costs = 0;
            company.buybackSpentToday(0);

            costs += runFactories(company);
            revenue += runSales(company);
            long wages = payWages(company);

            long profit = revenue - costs - wages;
            company.recordDay(revenue, costs, wages, profit);
            payDividends(company, profit);

            // Sentiment decays towards fair value, so a price that ran up on
            // one day of buying comes back unless the business justifies it.
            double drift = (1.0 - company.sentiment()) * config.shareReversionPercent() / 100.0;
            company.sentiment(company.sentiment() + drift);
            revalue(company);
            company.pushPrice(PRICE_HISTORY);

            if (company.cash() < config.bankruptcyDebtLimit() && bookValue(company) <= 0) {
                doomed.add(company);
            }
        }
        for (Company company : doomed) {
            company.bankrupt(true);
            liquidate(company, "자금난으로 도산");
        }
        save();
    }

    /** Production, input sourcing and upkeep. Returns what it all cost. */
    private long runFactories(Company company) {
        long costs = 0;
        for (Factory factory : company.factories()) {
            factory.lastOutput(0);
            CorpConfig.FactoryType type = config.factory(factory.typeId());
            if (type == null) {
                factory.idleReason("설비 정보 없음");
                continue;
            }
            long upkeep = config.upkeepAt(type, factory.level());
            if (company.cash() < upkeep) {
                factory.idleReason("유지비 부족");
                continue;
            }
            company.addCash(-upkeep);
            costs += upkeep;

            double capacity = config.outputAt(type, factory.level());
            double ratio = 1.0;
            for (Map.Entry<Material, Double> input : type.inputs().entrySet()) {
                double need = input.getValue() * capacity;
                long have = company.stockOf(input.getKey());
                if (have < need && company.autoBuyInputs()) {
                    costs += buyInput(company, input.getKey(), (long) Math.ceil(need - have));
                    have = company.stockOf(input.getKey());
                }
                if (need > 0) {
                    ratio = Math.min(ratio, have / need);
                }
            }
            if (ratio <= 0) {
                factory.idleReason("원료 없음");
                continue;
            }
            long output = (long) Math.floor(capacity * ratio);
            long room = config.warehouseCap() - company.stockOf(type.output());
            if (room <= 0) {
                factory.idleReason("창고 가득");
                continue;
            }
            output = Math.min(output, room);
            if (output <= 0) {
                factory.idleReason("생산량 0");
                continue;
            }
            for (Map.Entry<Material, Double> input : type.inputs().entrySet()) {
                company.addStock(input.getKey(), -(long) Math.ceil(input.getValue() * output));
            }
            company.addStock(type.output(), output);
            factory.lastOutput(output);
            factory.idleReason(ratio < 0.999 ? "원료 부족으로 감산" : null);
        }
        return costs;
    }

    /** Buys a missing input on the open market. Returns what it cost. */
    private long buyInput(Company company, Material material, long units) {
        MarketItem item = plugin.market().byMaterial(material);
        if (item == null || units <= 0 || company.cash() <= 0) {
            return 0;
        }
        MarketService.Fill fill = plugin.market().buyFor(item, units, company.cash());
        if (fill.units() <= 0) {
            return 0;
        }
        company.addStock(material, fill.units());
        company.addCash(-fill.gold());
        return fill.gold();
    }

    /** Sells the configured share of the warehouse. Returns the revenue. */
    private long runSales(Company company) {
        if (company.sellPercent() <= 0) {
            return 0;
        }
        long revenue = 0;
        for (Map.Entry<Material, Long> entry : new ArrayList<>(company.warehouse().entrySet())) {
            long offered = entry.getValue() * company.sellPercent() / 100;
            if (offered <= 0) {
                continue;
            }
            MarketItem item = plugin.market().byMaterial(entry.getKey());
            if (item == null) {
                continue;
            }
            MarketService.Fill fill = plugin.market().sellFor(item, offered);
            if (fill.units() <= 0) {
                continue;
            }
            company.addStock(entry.getKey(), -fill.units());
            company.addCash(fill.gold());
            revenue += fill.gold();
        }
        return revenue;
    }

    /**
     * Pays the staff, and lets go of the ones it could not pay.
     *
     * A company that cannot make payroll is not a company for long, and the
     * employee has to be able to walk away - otherwise being hired by a
     * failing business is a trap with no exit.
     */
    private long payWages(Company company) {
        if (config.wagePerEmployee() <= 0 || company.employees().isEmpty()) {
            return 0;
        }
        long paid = 0;
        List<UUID> quitting = new ArrayList<>();
        for (Map.Entry<UUID, Company.Employee> entry : company.employees().entrySet()) {
            Company.Employee employee = entry.getValue();
            boolean isCeo = entry.getKey().equals(company.ceo());
            long wage = Math.round(config.wagePerEmployee()
                    * (isCeo ? config.ceoWageMultiplier() : 1.0));
            if (wage <= 0) {
                continue;
            }
            if (company.cash() < wage) {
                employee.unpaidDays(employee.unpaidDays() + 1);
                Player online = plugin.getServer().getPlayer(entry.getKey());
                if (online != null) {
                    online.sendMessage(ChatColor.RED + "[기업] " + company.name()
                            + " 이(가) 임금을 주지 못했습니다. (" + employee.unpaidDays() + "일째)");
                }
                if (employee.unpaidDays() >= config.unpaidDaysBeforeQuit() && !isCeo) {
                    quitting.add(entry.getKey());
                }
                continue;
            }
            company.addCash(-wage);
            paid += wage;
            employee.unpaidDays(0);
            plugin.mailbox().giveGold(entry.getKey(), (int) Math.min(Integer.MAX_VALUE, wage),
                    company.name() + " 급여");
        }
        for (UUID leaver : quitting) {
            Company.Employee employee = company.employees().remove(leaver);
            employment.remove(leaver);
            Player online = plugin.getServer().getPlayer(leaver);
            if (online != null) {
                online.sendMessage(ChatColor.RED + "[기업] 임금 체불이 이어져 "
                        + company.name() + " 을(를) 떠났습니다.");
            }
            announce(company, ChatColor.RED + "[기업] " + (employee == null ? "직원" : employee.name())
                    + " 님이 임금 체불로 퇴사했습니다.");
        }
        return paid;
    }

    /** Hands a slice of the profit to whoever owns the company. */
    private void payDividends(Company company, long profit) {
        company.recordDividend(0);
        long publicShares = company.publicShares();
        if (profit <= 0 || publicShares <= 0 || company.dividendPercent() <= 0) {
            return;
        }
        long pool = Math.min(company.cash(), profit * company.dividendPercent() / 100);
        if (pool <= 0) {
            return;
        }
        long perShare = pool / publicShares;
        if (perShare < config.minDividendPerShare()) {
            // Too thin to be worth the message; it stays as retained earnings
            // and shows up in the share price instead.
            return;
        }
        long paid = 0;
        for (Map.Entry<UUID, Long> entry : new ArrayList<>(company.holders().entrySet())) {
            if (entry.getKey().equals(company.id())) {
                continue;
            }
            long amount = perShare * entry.getValue();
            if (amount <= 0) {
                continue;
            }
            paid += amount;
            payHolder(entry.getKey(), amount, company.name() + " 배당");
        }
        company.addCash(-paid);
        company.recordDividend(paid);
    }

    private void expireOffers() {
        long now = System.currentTimeMillis();
        Iterator<Offer> it = offers.iterator();
        while (it.hasNext()) {
            if (it.next().expiresAt() <= now) {
                it.remove();
            }
        }
    }

    private boolean guard(Player player) {
        if (!enabled()) {
            player.sendMessage(ChatColor.RED + "[기업] 이 서버에서는 기업 기능을 쓸 수 없습니다.");
            return false;
        }
        return true;
    }

    Map<UUID, Company> companyMap() {
        return companies;
    }

    List<Offer> offerList() {
        return offers;
    }

    boolean seededFlag() {
        return seeded;
    }

    void seededFlag(boolean seeded) {
        this.seeded = seeded;
    }
}
