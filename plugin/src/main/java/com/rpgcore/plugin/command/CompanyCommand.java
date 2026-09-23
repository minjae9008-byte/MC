package com.rpgcore.plugin.command;

import com.rpgcore.plugin.RpgCorePlugin;
import com.rpgcore.plugin.corp.Company;
import com.rpgcore.plugin.corp.CorpConfig;
import com.rpgcore.plugin.corp.CorpService;
import com.rpgcore.plugin.corp.Factory;
import com.rpgcore.plugin.economy.MarketService;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * /company - everything about running one.
 *
 * The screen covers the common cases; this covers the ones that need a name
 * or a number typed, and it is the only way to do the things that should take
 * a moment's thought - hiring, issuing shares, and buying somebody else.
 */
public final class CompanyCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = List.of(
            "create", "info", "list", "hire", "accept", "leave", "fire", "promote", "demote",
            "transfer", "deposit", "withdraw", "loan", "repay", "factory", "supply", "sell",
            "policy", "issue", "acquire", "offers", "absorb", "disband", "help");

    private final RpgCorePlugin plugin;

    public CompanyCommand(RpgCorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("이 명령어는 플레이어만 사용할 수 있습니다.");
            return true;
        }
        if (!plugin.corps().enabled()) {
            player.sendMessage(ChatColor.RED + "[기업] 이 서버에서는 기업 기능을 쓸 수 없습니다.");
            return true;
        }
        CorpService corps = plugin.corps();
        if (args.length == 0) {
            plugin.companyMenu().open(player);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "create" -> {
                if (args.length < 2) {
                    player.sendMessage(ChatColor.YELLOW + "/company create <이름>");
                    player.sendMessage(ChatColor.GRAY + "  창업 자본금 "
                            + corps.config().createCost() + "골드가 듭니다.");
                    return true;
                }
                corps.create(player, String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)));
            }
            case "info" -> info(player, args.length > 1 ? corps.find(args[1]) : corps.employerOf(player));
            case "list" -> list(player);
            case "hire" -> {
                if (args.length < 2) {
                    player.sendMessage(ChatColor.YELLOW + "/company hire <플레이어>");
                    return true;
                }
                Player target = plugin.getServer().getPlayerExact(args[1]);
                if (target == null) {
                    player.sendMessage(ChatColor.RED + "[기업] 온라인이 아닌 플레이어입니다: " + args[1]);
                    return true;
                }
                corps.invite(player, target);
            }
            case "accept" -> corps.accept(player);
            case "leave" -> corps.leave(player);
            case "fire" -> {
                if (args.length < 2) {
                    player.sendMessage(ChatColor.YELLOW + "/company fire <플레이어>");
                    return true;
                }
                corps.fire(player, args[1]);
            }
            case "promote" -> {
                if (args.length < 2) {
                    player.sendMessage(ChatColor.YELLOW + "/company promote <플레이어>");
                    return true;
                }
                corps.setRole(player, args[1], Company.Role.DIRECTOR);
            }
            case "demote" -> {
                if (args.length < 2) {
                    player.sendMessage(ChatColor.YELLOW + "/company demote <플레이어>");
                    return true;
                }
                corps.setRole(player, args[1], Company.Role.STAFF);
            }
            case "transfer" -> {
                if (args.length < 2) {
                    player.sendMessage(ChatColor.YELLOW + "/company transfer <플레이어>");
                    return true;
                }
                corps.transfer(player, args[1]);
            }
            case "deposit" -> corps.deposit(player, amount(player, args, 1));
            case "withdraw" -> corps.withdraw(player, amount(player, args, 1));
            case "loan" -> {
                if (args.length < 2) {
                    Company mine = corps.employerOf(player);
                    player.sendMessage(ChatColor.YELLOW + "/company loan <금액> [일수]");
                    if (mine != null) {
                        var account = corps.bankAccount(mine);
                        player.sendMessage(ChatColor.GRAY + "  자산 "
                                + comma(Math.round(corps.assets(mine))) + " · 한도 "
                                + comma(plugin.bank().creditLimit(account)) + " · 기존 채무 "
                                + comma(corps.debt(mine)) + " · 등급 "
                                + plugin.bank().grade(account).name());
                    }
                    return true;
                }
                corps.borrow(player, parse(args[1], 0), args.length > 2 ? (int) parse(args[2], 7) : 7);
            }
            case "repay" -> {
                Company mine = corps.employerOf(player);
                long owed = mine == null ? 0 : corps.debt(mine);
                long want = args.length > 1 && !args[1].equalsIgnoreCase("all")
                        ? parse(args[1], 0) : owed;
                corps.repayDebt(player, want);
            }
            case "supply" -> corps.supply(player, args.length > 1 && args[1].equalsIgnoreCase("all"));
            case "sell" -> {
                if (args.length < 2) {
                    player.sendMessage(ChatColor.YELLOW + "/company sell <품목> [개수|all]");
                    return true;
                }
                Material material = Material.matchMaterial(args[1]);
                if (material == null) {
                    player.sendMessage(ChatColor.RED + "[기업] 그런 품목이 없습니다: " + args[1]);
                    return true;
                }
                long units = args.length > 2 && !args[2].equalsIgnoreCase("all")
                        ? parse(args[2], 0) : Long.MAX_VALUE;
                corps.sellStock(player, material, units);
            }
            case "factory" -> factory(player, args);
            case "policy" -> policy(player, args);
            case "issue" -> corps.issueShares(player, amount(player, args, 1));
            case "acquire" -> {
                if (args.length < 2) {
                    player.sendMessage(ChatColor.YELLOW + "/company acquire <회사|티커> [프리미엄%]");
                    return true;
                }
                double premium = args.length > 2 ? parse(args[2], 10) : 10;
                corps.offerTakeover(player, corps.find(args[1]), premium);
            }
            case "offers" -> offers(player, args);
            case "absorb" -> {
                if (args.length < 2) {
                    player.sendMessage(ChatColor.YELLOW + "/company absorb <회사|티커>");
                    return true;
                }
                corps.absorb(player, corps.find(args[1]));
            }
            case "disband" -> corps.disband(player);
            default -> help(player);
        }
        return true;
    }

    private void factory(Player player, String[] args) {
        CorpService corps = plugin.corps();
        Company company = corps.employerOf(player);
        if (company == null) {
            player.sendMessage(ChatColor.RED + "[기업] 속한 회사가 없습니다.");
            return;
        }
        if (args.length < 2 || args[1].equalsIgnoreCase("list")) {
            player.sendMessage(ChatColor.GOLD + "===== 보유 공장 =====");
            if (company.factories().isEmpty()) {
                player.sendMessage(ChatColor.GRAY + " 없습니다. /company factory build <종류>");
            }
            for (int i = 0; i < company.factories().size(); i++) {
                Factory factory = company.factories().get(i);
                CorpConfig.FactoryType type = corps.config().factory(factory.typeId());
                player.sendMessage(ChatColor.GRAY + " " + (i + 1) + ") "
                        + (type == null ? factory.typeId() : type.name())
                        + " Lv." + factory.level()
                        + (type == null ? "" : ChatColor.DARK_GRAY + " 하루 "
                        + Math.round(corps.config().outputAt(type, factory.level())) + "개")
                        + (factory.idleReason() == null ? "" : ChatColor.RED + " [" + factory.idleReason() + "]"));
            }
            player.sendMessage(ChatColor.GOLD + "===== 지을 수 있는 공장 =====");
            for (CorpConfig.FactoryType type : corps.config().factories()) {
                player.sendMessage(ChatColor.GRAY + " " + type.id() + ChatColor.DARK_GRAY + " - "
                        + type.name() + " · " + type.output().getKey().getKey() + " "
                        + Math.round(type.perDay()) + "개/일 · 건설비 " + type.buildCost());
            }
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "build" -> {
                if (args.length < 3) {
                    player.sendMessage(ChatColor.YELLOW + "/company factory build <종류>");
                    return;
                }
                corps.buildFactory(player, args[2]);
            }
            case "upgrade" -> corps.upgradeFactory(player, (int) parse(args.length > 2 ? args[2] : "0", 0) - 1);
            case "sell" -> corps.sellFactory(player, (int) parse(args.length > 2 ? args[2] : "0", 0) - 1);
            default -> player.sendMessage(ChatColor.YELLOW
                    + "/company factory list | build <종류> | upgrade <번호> | sell <번호>");
        }
    }

    private void policy(Player player, String[] args) {
        Company company = plugin.corps().employerOf(player);
        if (company == null || !company.manages(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "[기업] 대표나 임원만 정책을 바꿀 수 있습니다.");
            return;
        }
        if (args.length < 2) {
            player.sendMessage(ChatColor.YELLOW + "/company policy sell <0-100> | dividend <0-100> | autobuy");
            player.sendMessage(ChatColor.GRAY + "  지금: 출고 " + company.sellPercent()
                    + "% · 배당 " + company.dividendPercent() + "% · 원료 자동조달 "
                    + (company.autoBuyInputs() ? "켜짐" : "꺼짐"));
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "sell" -> {
                company.sellPercent((int) parse(args.length > 2 ? args[2] : "100", 100));
                player.sendMessage(ChatColor.GREEN + "[기업] 출고 비율을 " + company.sellPercent() + "% 로.");
            }
            case "dividend" -> {
                company.dividendPercent((int) parse(args.length > 2 ? args[2] : "0", 0));
                player.sendMessage(ChatColor.GREEN + "[기업] 배당 비율을 " + company.dividendPercent() + "% 로.");
            }
            case "autobuy" -> {
                company.autoBuyInputs(!company.autoBuyInputs());
                player.sendMessage(ChatColor.GREEN + "[기업] 원료 자동 조달 "
                        + (company.autoBuyInputs() ? "켜짐" : "꺼짐"));
            }
            default -> player.sendMessage(ChatColor.YELLOW + "sell | dividend | autobuy");
        }
        plugin.corps().save();
    }

    private void offers(Player player, String[] args) {
        CorpService corps = plugin.corps();
        Company company = corps.employerOf(player);
        if (company == null) {
            player.sendMessage(ChatColor.RED + "[기업] 속한 회사가 없습니다.");
            return;
        }
        List<CorpService.Offer> pending = corps.offersFor(company);
        if (args.length >= 3) {
            int index = (int) parse(args[2], 0) - 1;
            corps.respondToOffer(player, index, args[1].equalsIgnoreCase("accept"));
            return;
        }
        player.sendMessage(ChatColor.GOLD + "===== 받은 인수 제안 =====");
        if (pending.isEmpty()) {
            player.sendMessage(ChatColor.GRAY + " 없습니다.");
            return;
        }
        for (int i = 0; i < pending.size(); i++) {
            CorpService.Offer offer = pending.get(i);
            Company acquirer = corps.byId(offer.acquirer());
            player.sendMessage(ChatColor.GRAY + " " + (i + 1) + ") "
                    + (acquirer == null ? "?" : acquirer.name()) + " · 주당 "
                    + offer.pricePerShare() + " · 총 " + offer.total()
                    + " (프리미엄 +" + (int) offer.premium() + "%)");
        }
        player.sendMessage(ChatColor.YELLOW + " /company offers accept <번호>"
                + ChatColor.GRAY + " 또는 " + ChatColor.YELLOW + "reject <번호>");
    }

    private void info(Player player, Company company) {
        if (company == null) {
            player.sendMessage(ChatColor.RED + "[기업] 그런 회사가 없습니다. /company list 로 확인하세요.");
            return;
        }
        CorpService corps = plugin.corps();
        player.sendMessage(ChatColor.GOLD + "===== " + company.name() + " (" + company.ticker() + ") =====");
        player.sendMessage(ChatColor.WHITE + " 현금 " + ChatColor.YELLOW + comma(company.cash())
                + ChatColor.GRAY + " · 창고 " + comma(Math.round(corps.warehouseValue(company)))
                + " · 설비 " + comma(Math.round(corps.factoryValue(company))));
        player.sendMessage(ChatColor.WHITE + " 자산 " + ChatColor.YELLOW
                + comma(Math.round(corps.assets(company))) + ChatColor.GRAY + " · 부채 "
                + (corps.debt(company) > 0 ? ChatColor.RED : ChatColor.GRAY)
                + comma(corps.debt(company)) + ChatColor.GRAY + " · 자본 "
                + (corps.equity(company) < 0 ? ChatColor.RED : ChatColor.WHITE)
                + comma(Math.round(corps.equity(company)))
                + ChatColor.GRAY + " · 부채비율 " + ratioOf(corps.debtRatioPercent(company))
                + (company.watchlisted() ? ChatColor.RED + "  [관리종목]" : "")
                + (company.stateOwned() ? ChatColor.AQUA + "  [공기업]" : ""));
        player.sendMessage(ChatColor.WHITE + " 기업가치 " + ChatColor.YELLOW
                + comma(Math.round(corps.fairValue(company)))
                + ChatColor.GRAY + " · 주가 " + MarketService.money(company.sharePrice())
                + " · 발행 " + comma(company.sharesIssued()) + "주 (자사주 "
                + comma(company.treasuryShares()) + ")");
        player.sendMessage(ChatColor.WHITE + " 어제 매출 " + ChatColor.YELLOW + comma(company.lastRevenue())
                + ChatColor.GRAY + " · 비용 " + comma(company.lastCosts())
                + " · 임금 " + comma(company.lastWages())
                + " · 이익 " + comma(company.lastProfit()));
        player.sendMessage(ChatColor.WHITE + " 배당 " + ChatColor.YELLOW + company.dividendPercent()
                + "%" + ChatColor.GRAY + " · 출고 " + company.sellPercent()
                + "% · 원료 자동조달 " + (company.autoBuyInputs() ? "켜짐" : "꺼짐"));
        if (!company.factories().isEmpty()) {
            player.sendMessage(ChatColor.GRAY + " 공장 " + company.factories().size() + "개:");
            for (Factory factory : company.factories()) {
                CorpConfig.FactoryType type = corps.config().factory(factory.typeId());
                player.sendMessage(ChatColor.DARK_GRAY + "  " + (type == null ? factory.typeId() : type.name())
                        + " Lv." + factory.level() + " · 어제 " + comma(factory.lastOutput()) + "개"
                        + (factory.idleReason() == null ? "" : " [" + factory.idleReason() + "]"));
            }
        }
        if (!company.warehouse().isEmpty()) {
            StringBuilder line = new StringBuilder(ChatColor.GRAY + " 창고: " + ChatColor.DARK_GRAY);
            int shown = 0;
            for (Map.Entry<Material, Long> entry : company.warehouse().entrySet()) {
                if (shown++ >= 6) {
                    line.append("...");
                    break;
                }
                line.append(entry.getKey().getKey().getKey()).append(" ")
                        .append(comma(entry.getValue())).append("  ");
            }
            player.sendMessage(line.toString());
        }
        long mine = company.sharesOf(player.getUniqueId());
        if (mine > 0) {
            player.sendMessage(ChatColor.GREEN + " 내 지분 " + comma(mine) + "주 ("
                    + String.format(Locale.ROOT, "%.1f%%",
                    mine * 100.0 / Math.max(1, company.sharesIssued())) + ")");
        }
    }

    private void list(Player player) {
        player.sendMessage(ChatColor.GOLD + "===== 기업 목록 =====");
        for (Company company : plugin.corps().listed()) {
            player.sendMessage(ChatColor.GRAY + " " + company.ticker() + ChatColor.DARK_GRAY + " "
                    + company.name() + " · 주가 " + MarketService.money(company.sharePrice())
                    + " · 시총 " + comma(Math.round(plugin.corps().marketCap(company)))
                    + " · 공장 " + company.factories().size()
                    + (company.stateOwned() ? " · 공기업"
                    : company.npc() ? " · 공모" : "")
                    + (company.watchlisted() ? ChatColor.RED + " · 관리종목" : ""));
        }
        player.sendMessage(ChatColor.GRAY + " /stocks 로 거래소 화면을 엽니다.");
    }

    private void help(Player player) {
        player.sendMessage(ChatColor.GOLD + "===== /company =====");
        player.sendMessage(ChatColor.GRAY + " /company" + ChatColor.DARK_GRAY + " - 회사 화면");
        player.sendMessage(ChatColor.GRAY + " /company create <이름> · info [회사] · list");
        player.sendMessage(ChatColor.GRAY + " /company hire <플레이어> · accept · leave · fire <플레이어>");
        player.sendMessage(ChatColor.GRAY + " /company promote|demote|transfer <플레이어>");
        player.sendMessage(ChatColor.GRAY + " /company deposit|withdraw <금액>");
        player.sendMessage(ChatColor.GRAY + " /company loan <금액> [일수] · repay <금액|all>"
                + ChatColor.DARK_GRAY + " - 회사 명의 대출");
        player.sendMessage(ChatColor.GRAY + " /company factory list|build <종류>|upgrade <번호>|sell <번호>");
        player.sendMessage(ChatColor.GRAY + " /company supply [all]" + ChatColor.DARK_GRAY + " - 손에 든 물건 납품");
        player.sendMessage(ChatColor.GRAY + " /company sell <품목> [개수|all]" + ChatColor.DARK_GRAY + " - 창고 출고");
        player.sendMessage(ChatColor.GRAY + " /company policy sell|dividend <0-100> | autobuy");
        player.sendMessage(ChatColor.GRAY + " /company issue <주식수>" + ChatColor.DARK_GRAY + " - 신주 발행");
        player.sendMessage(ChatColor.GRAY + " /company acquire <회사> [프리미엄%] · offers · absorb <회사>");
        player.sendMessage(ChatColor.GRAY + " /company disband");
    }

    private long amount(Player player, String[] args, int index) {
        if (args.length <= index) {
            player.sendMessage(ChatColor.YELLOW + "금액을 적어주세요.");
            return 0;
        }
        return parse(args[index], 0);
    }

    private static long parse(String text, long fallback) {
        try {
            return Long.parseLong(text.replace(",", ""));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String comma(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    private static String ratioOf(double percent) {
        return percent >= Double.MAX_VALUE / 2
                ? ChatColor.RED + "자본잠식"
                : String.format(Locale.ROOT, "%.0f%%", percent);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return prefixed(SUBS, args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            switch (sub) {
                case "factory" -> {
                    return prefixed(List.of("list", "build", "upgrade", "sell"), args[1]);
                }
                case "policy" -> {
                    return prefixed(List.of("sell", "dividend", "autobuy"), args[1]);
                }
                case "offers" -> {
                    return prefixed(List.of("accept", "reject"), args[1]);
                }
                case "acquire", "absorb", "info" -> {
                    List<String> tickers = new ArrayList<>();
                    for (Company company : plugin.corps().all()) {
                        tickers.add(company.ticker());
                    }
                    return prefixed(tickers, args[1]);
                }
                case "hire", "fire", "promote", "demote", "transfer" -> {
                    List<String> names = new ArrayList<>();
                    for (Player online : plugin.getServer().getOnlinePlayers()) {
                        names.add(online.getName());
                    }
                    return prefixed(names, args[1]);
                }
                default -> {
                    return List.of();
                }
            }
        }
        if (args.length == 3 && sub.equals("factory") && args[1].equalsIgnoreCase("build")) {
            List<String> ids = new ArrayList<>();
            for (CorpConfig.FactoryType type : plugin.corps().config().factories()) {
                ids.add(type.id());
            }
            return prefixed(ids, args[2]);
        }
        return List.of();
    }

    private static List<String> prefixed(List<String> options, String typed) {
        String prefix = typed.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                out.add(option);
            }
        }
        return out;
    }
}
