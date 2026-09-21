package com.rpgcore.plugin.economy;

import com.rpgcore.plugin.RpgCorePlugin;
import com.rpgcore.plugin.util.DeferredSave;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * The market: where goods meet gold.
 *
 * It is a state counter rather than a player-to-player board - the auction
 * house already does player-to-player. That is deliberate. A market with a
 * price of its own gives the server something the auction house cannot: a
 * number everybody can see that says what a thing is worth today, and which
 * moves when the server as a whole digs up more of it or runs it down.
 *
 * Three rules keep it from being a money printer:
 *
 *   1. It buys lower than it sells (the spread), so round-tripping a stack
 *      through it loses money.
 *   2. It has a finite purse. Selling into it drains gold that buying put
 *      there; when the purse is empty it stops buying, and the central bank
 *      has to print to refill it - which shows up as inflation rather than
 *      being free.
 *   3. It has finite shelf space. Sell enough of one thing and the price
 *      falls to the floor and it stops taking any more.
 *
 * Everything here runs on the main thread, so a stock level is read and
 * written without a lock; the only work off the main thread is the file write.
 */
public final class MarketService {

    /** A priced order, before anybody has committed to it. */
    public record Quote(int units, long gross, long tax, long total, double averagePrice, String refusal) {

        public boolean ok() {
            return refusal == null && units > 0;
        }
    }

    /** One entry in the shock log, for the dashboard. */
    public record Shock(int day, String item, String text, double magnitude) {
    }

    private static final int SHOCK_LOG = 10;

    private final RpgCorePlugin plugin;
    private final EconomyConfig config;
    private final MarketStorage storage;
    private final DeferredSave writer;
    private final Random random = new Random();

    /** Insertion order is the file's order, which is the order players see. */
    private final Map<String, MarketItem> items = new LinkedHashMap<>();
    /**
     * The same goods by material. Kept because {@link #recordProduction} runs
     * on every broken block, and a miss there must cost one map lookup rather
     * than a walk over a hundred goods looking for a prefix.
     */
    private final Map<Material, MarketItem> byMaterial = new java.util.EnumMap<>(Material.class);
    /** Goods held back from the shelf, for the central bank to release. */
    private final Map<String, Double> reserve = new LinkedHashMap<>();
    private final List<Shock> shocks = new ArrayList<>();

    /** The market's purse. Buying fills it, selling empties it. */
    private long treasury;
    /** Gold the central bank has had to print to keep the purse solvent. */
    private long printed;
    /** Tax and fees taken out of circulation, cumulative. */
    private long taxTake;
    /**
     * The price level every good is multiplied by. Moved only by the central
     * bank's reading of money growth - this is the quantity theory's P.
     */
    private double priceLevel = 1.0;

    public MarketService(RpgCorePlugin plugin, EconomyConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.storage = new MarketStorage(plugin);
        this.writer = new DeferredSave(plugin, MarketStorage.FILE, () -> storage.build(this));
    }

    public boolean enabled() {
        return plugin.rpgConfig().marketEnabled();
    }

    // ------------------------------------------------------------------ load

    public void load() {
        items.clear();
        byMaterial.clear();
        reserve.clear();
        for (EconomyConfig.ItemDef def : config.items()) {
            MarketItem item = new MarketItem(def, config);
            items.put(item.id(), item);
            byMaterial.put(item.material(), item);
        }
        treasury = config.treasuryStart();
        storage.restore(this);
        for (MarketItem item : items.values()) {
            item.recompute(config);
        }
        plugin.getLogger().info("Market loaded: " + items.size() + " goods, purse "
                + treasury + " gold.");
    }

    /**
     * Re-derives base prices and supply levels after a reload, keeping live
     * stock. The two derived numbers both come from the config, so changing
     * the wage or the reference player count reprices the whole market at once.
     */
    public void applyConfig() {
        for (MarketItem item : items.values()) {
            item.applyConfig(config);
            item.recompute(config);
        }
        save();
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

    // --------------------------------------------------------------- reading

    public Map<String, MarketItem> items() {
        return items;
    }

    public int size() {
        return items.size();
    }

    public MarketItem byId(String id) {
        if (id == null) {
            return null;
        }
        String key = id.toLowerCase(Locale.ROOT).replace("minecraft:", "").trim();
        MarketItem exact = items.get(key);
        if (exact != null) {
            return exact;
        }
        // A player typing /market buy iron is not going to type iron_ingot, so
        // a unique prefix or substring counts. Ambiguity returns nothing
        // rather than guessing, because guessing spends somebody's gold.
        MarketItem partial = null;
        for (MarketItem item : items.values()) {
            if (item.id().startsWith(key)) {
                if (partial != null) {
                    return null;
                }
                partial = item;
            }
        }
        return partial;
    }

    public MarketItem byMaterial(Material material) {
        return material == null ? null : byMaterial.get(material);
    }

    public List<MarketItem> inCategory(String category) {
        List<MarketItem> out = new ArrayList<>();
        for (MarketItem item : items.values()) {
            if (item.category().equalsIgnoreCase(category)) {
                out.add(item);
            }
        }
        return out;
    }

    /** Biggest movers today, gainers or fallers. */
    public List<MarketItem> movers(boolean gainers, int count) {
        List<MarketItem> sorted = new ArrayList<>(items.values());
        sorted.sort(gainers
                ? Comparator.comparingDouble(MarketItem::changePercent).reversed()
                : Comparator.comparingDouble(MarketItem::changePercent));
        return sorted.subList(0, Math.min(count, sorted.size()));
    }

    /** Everything on the shelf, valued at the mid price. */
    public double totalMarketCap() {
        double sum = 0;
        for (MarketItem item : items.values()) {
            sum += item.marketCap();
        }
        return sum;
    }

    public long treasury() {
        return treasury;
    }

    public long printed() {
        return printed;
    }

    public long taxTake() {
        return taxTake;
    }

    public double priceLevel() {
        return priceLevel;
    }

    /**
     * Moves the whole price level by a fraction, and reprices everything.
     *
     * This is the channel from money to prices: MV = PQ, so when the money
     * supply grows faster than what the server produces, P has to give. It is
     * the reason the gold that mob kills and level-ups mint has a cost at all
     * - without it a server can print forever and nothing ever gets dearer.
     */
    void driftPriceLevel(double fraction) {
        if (Math.abs(fraction) < 0.00001) {
            return;
        }
        priceLevel = Math.clamp(priceLevel * (1 + fraction), 0.05, 50.0);
        for (MarketItem item : items.values()) {
            item.priceLevel(priceLevel);
            item.recompute(config);
        }
    }

    public List<Shock> shocks() {
        return List.copyOf(shocks);
    }

    public double reserveOf(MarketItem item) {
        return reserve.getOrDefault(item.id(), 0.0);
    }

    /** The whole national reserve as a share of one day's base supply. */
    public double reservePercent() {
        double held = 0;
        double base = 0;
        for (MarketItem item : items.values()) {
            held += reserve.getOrDefault(item.id(), 0.0);
            base += item.baseStock();
        }
        return base <= 0 ? 0 : held / base * 100.0;
    }

    // --------------------------------------------------------------- quoting

    /**
     * Prices an order without committing to it.
     *
     * The walk is one unit at a time rather than {@code units x price}. A
     * single price for the whole order would let one player buy the entire
     * warehouse at the price the first unit cost, which is exactly the trade
     * that breaks markets like this one.
     */
    public Quote quoteBuy(MarketItem item, int units) {
        if (item == null) {
            return refusal("그런 품목이 없습니다.");
        }
        int want = Math.clamp(units, 0, config.maxUnitsPerOrder());
        if (want <= 0) {
            return refusal("수량은 1 이상이어야 합니다.");
        }
        int available = (int) Math.min(want, Math.floor(item.stock()));
        if (available <= 0) {
            return refusal("재고가 없습니다. 값이 오를 때까지 아무도 팔지 않았습니다.");
        }
        double gross = 0;
        for (int i = 0; i < available; i++) {
            gross += item.askAt(item.stock() - i, config);
        }
        long grossGold = Math.max(1, Math.round(Math.ceil(gross)));
        long tax = Math.round(grossGold * config.salesTaxPercent() / 100.0);
        if (grossGold + tax > Integer.MAX_VALUE) {
            // A balance is a single int, so a bill this size cannot be paid
            // and must not be half-paid either.
            return refusal("한 번에 사기에는 너무 큽니다. 수량을 줄이세요.");
        }
        return new Quote(available, grossGold, tax, grossGold + tax,
                grossGold / (double) available, null);
    }

    public Quote quoteSell(MarketItem item, int units) {
        if (item == null) {
            return refusal("그런 품목이 없습니다.");
        }
        int want = Math.clamp(units, 0, config.maxUnitsPerOrder());
        if (want <= 0) {
            return refusal("수량은 1 이상이어야 합니다.");
        }
        double room = item.baseStock() * config.maxStockMultiple() - item.stock();
        int fits = (int) Math.min(want, Math.max(0, Math.floor(room)));
        if (fits <= 0) {
            return refusal("공급 과잉입니다. 이 품목은 당분간 매입하지 않습니다.");
        }

        // Filled only as far as the purse reaches. A partial fill is the
        // honest answer to "the market cannot afford your truckload" - better
        // than refusing outright and better than promising gold that is not
        // there.
        double gross = 0;
        int taken = 0;
        double afterTaxShare = 1.0 - config.salesTaxPercent() / 100.0;
        for (int i = 0; i < fits; i++) {
            double next = gross + item.bidAt(item.stock() + i, config);
            // The purse only ever pays out the net; the tax half of the price
            // never leaves it. So the limit is on the net, not on the gross.
            if (next * afterTaxShare > treasury) {
                break;
            }
            gross = next;
            taken++;
        }
        if (taken <= 0) {
            return refusal("시장 금고가 비었습니다. 중앙은행이 채울 때까지 기다려야 합니다.");
        }
        long grossGold = Math.max(0, Math.round(Math.floor(gross)));
        long tax = Math.round(grossGold * config.salesTaxPercent() / 100.0);
        long net = Math.max(0, grossGold - tax);
        return new Quote(taken, grossGold, tax, net,
                grossGold / (double) taken, null);
    }

    private static Quote refusal(String why) {
        return new Quote(0, 0, 0, 0, 0, why);
    }

    // --------------------------------------------------------------- trading

    /** Buys, pays, and hands over. Returns false having said why. */
    public boolean buy(Player player, MarketItem item, int units) {
        if (!guard(player)) {
            return false;
        }
        Quote quote = quoteBuy(item, units);
        if (!quote.ok()) {
            player.sendMessage(ChatColor.RED + "[시장] " + quote.refusal());
            return false;
        }
        if (!plugin.economy().take(player, (int) Math.min(Integer.MAX_VALUE, quote.total()))) {
            player.sendMessage(ChatColor.RED + "[시장] 골드가 부족합니다. 필요 "
                    + quote.total() + ", 보유 " + plugin.economy().balance(player) + ".");
            return false;
        }

        item.stock(item.stock() - quote.units());
        item.countBought(quote.units());
        item.recompute(config);
        treasury += quote.total();
        taxTake += quote.tax();
        deliver(player, item.material(), quote.units());
        recordTrade(quote.gross());
        save();

        player.sendMessage(ChatColor.GREEN + "[시장] " + name(item) + " " + quote.units()
                + "개를 " + ChatColor.GOLD + quote.total() + plugin.rpgConfig().goldSymbol()
                + ChatColor.GREEN + " 에 샀습니다. " + ChatColor.GRAY + "(개당 "
                + money(quote.averagePrice()) + ", 세금 " + quote.tax() + ")");
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_TRADE, 0.8F, 1.1F);
        return true;
    }

    /** Sells from the player's inventory. Returns the number of units sold. */
    public int sell(Player player, MarketItem item, int units) {
        if (!guard(player)) {
            return 0;
        }
        int held = countPlain(player, item.material());
        if (held <= 0) {
            player.sendMessage(ChatColor.RED + "[시장] " + name(item) + " 을(를) 가지고 있지 않습니다."
                    + ChatColor.GRAY + " (이름을 바꾸거나 인챈트한 물건은 시장에서 안 삽니다)");
            return 0;
        }
        int want = Math.min(units, held);
        Quote quote = quoteSell(item, want);
        if (!quote.ok()) {
            player.sendMessage(ChatColor.RED + "[시장] " + quote.refusal());
            return 0;
        }
        if (!removePlain(player, item.material(), quote.units())) {
            player.sendMessage(ChatColor.RED + "[시장] 물건을 꺼내지 못했습니다. 다시 시도하세요.");
            return 0;
        }

        item.stock(item.stock() + quote.units());
        item.countSold(quote.units());
        item.recompute(config);
        treasury -= quote.total();
        taxTake += quote.tax();

        // The bank gets first claim on the proceeds of a player in arrears.
        // Without it a defaulter simply never touches their bank account again
        // and keeps trading, which makes a loan a gift.
        long seized = plugin.bank() == null ? 0 : plugin.bank().garnish(player, quote.total());
        long paid = quote.total() - seized;
        if (paid > 0) {
            plugin.economy().refund(player, (int) Math.min(Integer.MAX_VALUE, paid));
        }
        recordTrade(quote.gross());
        save();

        player.sendMessage(ChatColor.GREEN + "[시장] " + name(item) + " " + quote.units()
                + "개를 " + ChatColor.GOLD + paid + plugin.rpgConfig().goldSymbol()
                + ChatColor.GREEN + " 에 팔았습니다. " + ChatColor.GRAY + "(개당 "
                + money(quote.averagePrice()) + ", 세금 " + quote.tax()
                + (seized > 0 ? ", 연체 압류 " + seized : "") + ")");
        if (quote.units() < want) {
            player.sendMessage(ChatColor.GRAY + "[시장] " + (want - quote.units())
                    + "개는 시장이 더 받지 못했습니다. (금고 또는 창고 한계)");
        }
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_YES, 0.8F, 1.0F);
        return quote.units();
    }

    /** Sells the stack in the main hand - the fastest path there is. */
    public int sellHand(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held == null || held.getType().isAir()) {
            player.sendMessage(ChatColor.RED + "[시장] 손에 든 것이 없습니다.");
            return 0;
        }
        MarketItem item = byMaterial(held.getType());
        if (item == null) {
            player.sendMessage(ChatColor.RED + "[시장] 시장에서 취급하지 않는 품목입니다: "
                    + held.getType().getKey().getKey());
            return 0;
        }
        return sell(player, item, held.getAmount());
    }

    /** Sells every plain stack of one good the player is carrying. */
    public int sellAll(Player player, MarketItem item) {
        int held = countPlain(player, item.material());
        return held <= 0 ? sell(player, item, 1) : sell(player, item, held);
    }

    private boolean guard(Player player) {
        if (!enabled()) {
            player.sendMessage(ChatColor.RED + "[시장] 이 서버에서는 시장을 쓸 수 없습니다.");
            return false;
        }
        if (plugin.trades().sessionOf(player) != null) {
            player.sendMessage(ChatColor.RED + "[시장] 거래 중에는 시장을 쓸 수 없습니다.");
            return false;
        }
        return true;
    }

    private void recordTrade(long value) {
        if (plugin.macro() != null) {
            plugin.macro().recordTrade(value);
        }
    }

    // ------------------------------------------------------------- inventory

    /**
     * Everything sellable the player is carrying, counted in one pass.
     *
     * The market screen draws up to twenty-eight goods at once and each one
     * wants to know "how many of these have you got". Asked per good that is
     * twenty-eight scans of the whole inventory, and each one copies an item's
     * meta to decide whether it is plain - about a thousand meta copies to
     * draw one screen.
     *
     * "Sellable" excludes anything customised - see {@link #countPlain}.
     */
    public Map<Material, Integer> plainCounts(Player player) {
        Map<Material, Integer> counts = new java.util.EnumMap<>(Material.class);
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack == null || stack.getType().isAir() || !byMaterial.containsKey(stack.getType())) {
                continue;
            }
            if (isPlain(stack, stack.getType())) {
                counts.merge(stack.getType(), stack.getAmount(), Integer::sum);
            }
        }
        return counts;
    }

    /**
     * Counts one good.
     *
     * A renamed, enchanted or damaged stack is not the commodity the market
     * prices - it is somebody's named pickaxe - so it is not counted and not
     * taken. Selling those at the commodity price is how a player loses a
     * mending tool to a mistyped number.
     */
    public int countPlain(Player player, Material material) {
        int total = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (isPlain(stack, material)) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    private boolean removePlain(Player player, Material material, int units) {
        if (countPlain(player, material) < units) {
            return false;
        }
        int left = units;
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int slot = 0; slot < contents.length && left > 0; slot++) {
            ItemStack stack = contents[slot];
            if (!isPlain(stack, material)) {
                continue;
            }
            int take = Math.min(left, stack.getAmount());
            left -= take;
            if (take >= stack.getAmount()) {
                contents[slot] = null;
            } else {
                stack.setAmount(stack.getAmount() - take);
            }
        }
        player.getInventory().setStorageContents(contents);
        player.updateInventory();
        return left == 0;
    }

    private boolean isPlain(ItemStack stack, Material material) {
        if (stack == null || stack.getType() != material) {
            return false;
        }
        if (!stack.hasItemMeta()) {
            return true;
        }
        var meta = stack.getItemMeta();
        if (meta.hasDisplayName() || meta.hasEnchants()) {
            return false;
        }
        if (meta instanceof org.bukkit.inventory.meta.Damageable damageable && damageable.hasDamage()) {
            return false;
        }
        // Not simply "has no lore": the weight tooltip is written onto every
        // carried stack, so that test would make the market refuse to buy
        // anything at all on a server with the feature on.
        return plugin.weight().lore().onlyOwnLore(stack);
    }

    /** Hands over a purchase, mailing whatever will not fit. */
    private void deliver(Player player, Material material, int units) {
        int left = units;
        int max = Math.max(1, material.getMaxStackSize());
        while (left > 0) {
            int size = Math.min(max, left);
            left -= size;
            ItemStack stack = new ItemStack(material, size);
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
            for (ItemStack rest : leftover.values()) {
                // Never dropped on the floor: a player buying a stack while
                // standing over lava should not lose it to the purchase.
                plugin.mailbox().give(player.getUniqueId(), rest, "시장 구매분");
            }
        }
    }

    // ------------------------------------------------------- world production

    /**
     * A block came out of the ground, so there is more of it in the world.
     *
     * The tie between mining speed and supply is not only the starting stock:
     * a server that mines a lot of iron this hour has more iron in it, and the
     * price should know. One block is a rounding error on a warehouse, which
     * is the point - it takes a server-wide effort to move a price this way.
     */
    public void recordProduction(Material material) {
        if (!enabled() || config.productionWeight() <= 0) {
            return;
        }
        MarketItem item = byMaterial(material);
        if (item == null) {
            return;
        }
        item.stock(item.stock() + config.productionWeight());
        item.recompute(config);
        // Not saved here: this fires on every broken block, and the daily
        // pass writes the file anyway.
    }

    // ------------------------------------------------------------- daily pass

    /**
     * One economic day on the market: yesterday closes, the world produces,
     * sentiment drifts, and something may go wrong somewhere.
     */
    void dayTick(int day) {
        for (MarketItem item : items.values()) {
            item.closeDay();

            // Production and consumption, as one mean reversion. Above the
            // base level goods get used up; below it, the world keeps digging.
            double gap = item.baseStock() - item.stock();
            item.stock(item.stock() + gap * config.restockPercentPerDay() / 100.0);

            // A bounded random walk that pulls back towards 1.0. The pull is
            // what stops a run of good rolls becoming a permanent price level.
            double drift = (1.0 - item.sentiment()) * config.sentimentReversionPercent() / 100.0;
            double noise = random.nextGaussian() * config.volatilityPercent() / 100.0;
            item.sentiment(item.sentiment() + drift + noise);

            item.recompute(config);
            item.pushHistory(config.historyPoints());
        }
        maybeShock(day);
        refillTreasury();
        save();
    }

    /** Occasionally, something happens to one good. */
    private void maybeShock(int day) {
        if (!config.shockEnabled() || items.isEmpty()) {
            return;
        }
        if (random.nextInt(100) >= config.shockChancePercent()) {
            return;
        }
        List<MarketItem> pool = new ArrayList<>(items.values());
        MarketItem item = pool.get(random.nextInt(pool.size()));
        double magnitude = config.shockMinPercent()
                + random.nextDouble() * (config.shockMaxPercent() - config.shockMinPercent());
        boolean supplySide = random.nextBoolean();
        boolean good = random.nextBoolean();
        String text;
        if (supplySide) {
            double factor = good ? 1 + magnitude / 100.0 : 1 - magnitude / 100.0;
            item.stock(item.stock() * factor);
            // Deliberately not "a new ore vein": the same line has to read
            // sensibly for books, bread and shulker shells.
            text = good
                    ? "물량이 쏟아져 들어왔습니다 - 공급 급증"
                    : "공급선이 끊겼습니다 - 공급 급감";
        } else {
            double factor = good ? 1 + magnitude / 100.0 : 1 - magnitude / 100.0;
            item.sentiment(item.sentiment() * factor);
            text = good
                    ? "수요가 몰렸습니다 - 값이 뜁니다"
                    : "수요가 식었습니다 - 값이 빠집니다";
        }
        item.recompute(config);
        item.shockNote(text);
        shocks.add(0, new Shock(day, item.id(), text, magnitude));
        while (shocks.size() > SHOCK_LOG) {
            shocks.remove(shocks.size() - 1);
        }
        if (config.announceShocks()) {
            plugin.getServer().broadcastMessage(ChatColor.GOLD + "[시황] " + ChatColor.WHITE
                    + name(item) + ChatColor.GRAY + " - " + text + " ("
                    + String.format(Locale.ROOT, "%.0f", magnitude) + "%)");
        }
    }

    /**
     * Keeps the purse solvent by printing, and says so.
     *
     * This is where gold enters the economy in bulk, and it is deliberately
     * the only place that is loud about it: printing is how a server ends up
     * with inflation, and the dashboard's "발권" line is the receipt.
     */
    private void refillTreasury() {
        if (treasury >= config.treasuryFloor()) {
            return;
        }
        treasury += config.treasuryRefill();
        printed += config.treasuryRefill();
        if (plugin.macro() != null) {
            plugin.macro().recordPrinting(config.treasuryRefill());
        }
    }

    // ------------------------------------------------- central bank operations

    /**
     * Releases goods from the national reserve onto the shelf, or takes them
     * back off it. Positive units release (prices fall), negative absorb.
     *
     * Only goods move, never gold: the reserve is a warehouse, not a budget.
     * That keeps the operation honest - the state cannot conjure supply it
     * never bought, and when the reserve is empty the dashboard says so
     * instead of the price quietly being propped up by nothing.
     *
     * @return the units actually moved
     */
    double intervene(MarketItem item, double units) {
        double held = reserve.getOrDefault(item.id(), 0.0);
        double moved;
        if (units > 0) {
            moved = Math.min(units, held);
            if (moved <= 0) {
                return 0;
            }
            reserve.put(item.id(), held - moved);
            item.stock(item.stock() + moved);
        } else {
            // Never strips the shelf bare: buying the last unit into reserve
            // would send the price straight to the ceiling.
            moved = -Math.min(-units, Math.max(0, item.stock() - item.baseStock() * 0.1));
            if (moved >= 0) {
                return 0;
            }
            reserve.put(item.id(), held - moved);
            item.stock(item.stock() + moved);
        }
        item.recompute(config);
        return moved;
    }

    void seedReserve(double percentOfBase) {
        for (MarketItem item : items.values()) {
            reserve.putIfAbsent(item.id(), item.baseStock() * percentOfBase / 100.0);
        }
    }

    // ---------------------------------------------------------- persistence

    void restoreTreasury(long treasury, long printed, long taxTake) {
        this.treasury = Math.max(0, treasury);
        this.printed = Math.max(0, printed);
        this.taxTake = Math.max(0, taxTake);
    }

    void restorePriceLevel(double level) {
        this.priceLevel = Math.clamp(level, 0.05, 50.0);
        for (MarketItem item : items.values()) {
            item.priceLevel(priceLevel);
        }
    }

    void restoreReserve(String id, double units) {
        reserve.put(id, Math.max(0, units));
    }

    void restoreShock(Shock shock) {
        shocks.add(shock);
    }

    Map<String, Double> reserveView() {
        return reserve;
    }

    EconomyConfig config() {
        return config;
    }

    // -------------------------------------------------------------- display

    /** The item's Korean-facing name: its id, which is what commands take. */
    public String name(MarketItem item) {
        return item.id();
    }

    /** Money with as many decimals as it needs and no more. */
    public static String money(double amount) {
        if (amount >= 100) {
            return String.format(Locale.ROOT, "%,.0f", amount);
        }
        if (amount >= 10) {
            return String.format(Locale.ROOT, "%.1f", amount);
        }
        return String.format(Locale.ROOT, "%.2f", amount);
    }

    /** Signed percent, coloured green up and red down. */
    public static String change(double percent) {
        String text = String.format(Locale.ROOT, "%+.1f%%", percent);
        if (percent > 0.05) {
            return ChatColor.RED + "▲ " + text;
        }
        if (percent < -0.05) {
            return ChatColor.BLUE + "▼ " + text;
        }
        return ChatColor.GRAY + "- " + text;
    }
}
