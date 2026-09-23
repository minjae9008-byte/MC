package com.rpgcore.plugin.command;

import com.rpgcore.plugin.RpgCorePlugin;
import com.rpgcore.plugin.economy.MarketItem;
import com.rpgcore.plugin.economy.MarketMenu;
import com.rpgcore.plugin.economy.MarketService;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /market - the shop screen, and the same thing from the keyboard.
 *
 * The GUI is the front door; these subcommands exist because a player who
 * already knows they want 64 iron should not have to page through a menu to
 * say so, and because a price check is a question, not a shopping trip.
 */
public final class MarketCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS =
            List.of("buy", "sell", "sellhand", "sellall", "price", "top", "list", "help");

    private final RpgCorePlugin plugin;

    public MarketCommand(RpgCorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("이 명령어는 플레이어만 사용할 수 있습니다.");
            return true;
        }
        if (!plugin.market().enabled()) {
            player.sendMessage(ChatColor.RED + "[시장] 이 서버에서는 시장을 쓸 수 없습니다.");
            return true;
        }
        if (args.length == 0) {
            plugin.marketMenu().open(player);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "buy" -> {
                MarketItem item = require(player, args.length > 1 ? args[1] : null);
                if (item != null) {
                    plugin.market().buy(player, item, amount(args, 2, 1));
                }
            }
            case "sell" -> {
                MarketItem item = require(player, args.length > 1 ? args[1] : null);
                if (item == null) {
                    return true;
                }
                if (args.length > 2 && args[2].equalsIgnoreCase("all")) {
                    plugin.market().sellAll(player, item);
                } else {
                    plugin.market().sell(player, item, amount(args, 2, 1));
                }
            }
            case "sellall" -> {
                MarketItem item = require(player, args.length > 1 ? args[1] : null);
                if (item != null) {
                    plugin.market().sellAll(player, item);
                }
            }
            case "sellhand" -> plugin.market().sellHand(player);
            case "price" -> {
                MarketItem item = require(player, args.length > 1 ? args[1] : null);
                if (item != null) {
                    quote(player, item);
                }
            }
            case "top" -> top(player, args.length > 1 && args[1].startsWith("d"));
            case "list" -> list(player, args.length > 1 ? args[1] : null);
            default -> help(player);
        }
        return true;
    }

    private MarketItem require(Player player, String id) {
        if (id == null) {
            player.sendMessage(ChatColor.RED + "[시장] 품목을 적어주세요. 예: /market price diamond");
            return null;
        }
        MarketItem item = plugin.market().byId(id);
        if (item == null) {
            player.sendMessage(ChatColor.RED + "[시장] 그런 품목이 없거나 이름이 여러 개에 걸립니다: " + id);
            player.sendMessage(ChatColor.GRAY + "  /market list 로 목록을 볼 수 있습니다.");
        }
        return item;
    }

    private static int amount(String[] args, int index, int fallback) {
        if (args.length <= index) {
            return fallback;
        }
        try {
            return Math.max(1, Integer.parseInt(args[index]));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** The full picture for one good, including what an order would cost. */
    private void quote(Player player, MarketItem item) {
        MarketService market = plugin.market();
        var config = plugin.economyConfig();
        String symbol = plugin.rpgConfig().goldSymbol();
        player.sendMessage(ChatColor.GOLD + "===== " + item.id() + " =====");
        player.sendMessage(ChatColor.WHITE + " 살 때 " + ChatColor.GOLD
                + MarketService.money(item.ask(config)) + symbol + ChatColor.DARK_GRAY + "  /  "
                + ChatColor.WHITE + "팔 때 " + ChatColor.YELLOW
                + MarketService.money(item.bid(config)) + symbol
                + ChatColor.GRAY + "  (스프레드 " + MarketMenu.trim(config.spreadPercent()) + "%)");
        player.sendMessage(ChatColor.GRAY + " 전일 대비 " + MarketService.change(item.changePercent())
                + ChatColor.GRAY + " · 기준가 " + MarketService.money(item.basePrice())
                + " 대비 " + String.format(Locale.ROOT, "%.0f%%", item.valuationPercent()));
        String chart = item.sparkline();
        if (!chart.isEmpty()) {
            player.sendMessage(ChatColor.AQUA + " " + chart + ChatColor.DARK_GRAY
                    + " (" + item.history().size() + "일)");
        }
        player.sendMessage(ChatColor.GRAY + " 재고 " + String.format(Locale.ROOT, "%,.0f", item.stock())
                + " / 기준 " + String.format(Locale.ROOT, "%,.0f", item.baseStock())
                + " (" + String.format(Locale.ROOT, "%.0f%%", item.supplyPercent()) + ")");
        player.sendMessage(ChatColor.GRAY + " 시간당 채굴 " + MarketMenu.trim(item.perHour())
                + "개 · 수요탄력성 " + MarketMenu.trim(item.elasticity())
                + " · 수요계수 " + MarketMenu.trim(item.demand()));
        var buy64 = market.quoteBuy(item, 64);
        if (buy64.ok()) {
            player.sendMessage(ChatColor.GRAY + " 64개 구매 " + ChatColor.WHITE + buy64.total() + symbol
                    + ChatColor.DARK_GRAY + " (개당 " + MarketService.money(buy64.averagePrice())
                    + ", 세금 " + buy64.tax() + ")");
        }
        int held = market.countPlain(player, item.material());
        if (held > 0) {
            var sellAll = market.quoteSell(item, held);
            player.sendMessage(ChatColor.GRAY + " 보유 " + held + "개 전량 판매 "
                    + ChatColor.WHITE + sellAll.total() + symbol);
        }
        if (item.shockNote() != null) {
            player.sendMessage(ChatColor.LIGHT_PURPLE + " [시황] " + item.shockNote());
        }
    }

    private void top(Player player, boolean fallers) {
        player.sendMessage(ChatColor.GOLD + "===== " + (fallers ? "급락" : "급등") + " 10 =====");
        int rank = 1;
        for (MarketItem item : plugin.market().movers(!fallers, 10)) {
            player.sendMessage(ChatColor.GRAY + " " + rank++ + ". " + ChatColor.WHITE + item.id()
                    + " " + MarketService.change(item.changePercent()) + ChatColor.GRAY + " "
                    + MarketService.money(item.mid()) + plugin.rpgConfig().goldSymbol());
        }
    }

    private void list(Player player, String category) {
        if (category == null) {
            player.sendMessage(ChatColor.GOLD + "===== 시장 분류 =====");
            for (var entry : plugin.economyConfig().categories()) {
                player.sendMessage(ChatColor.GRAY + " " + entry.id() + ChatColor.DARK_GRAY + " - "
                        + entry.name() + " (" + plugin.market().inCategory(entry.id()).size() + "종)");
            }
            player.sendMessage(ChatColor.GRAY + " /market list <분류> 로 품목을 봅니다.");
            return;
        }
        List<MarketItem> goods = plugin.market().inCategory(category);
        if (goods.isEmpty()) {
            player.sendMessage(ChatColor.RED + "[시장] 그런 분류가 없습니다: " + category);
            return;
        }
        player.sendMessage(ChatColor.GOLD + "===== " + category + " (" + goods.size() + "종) =====");
        StringBuilder line = new StringBuilder(ChatColor.GRAY.toString());
        for (int i = 0; i < goods.size(); i++) {
            MarketItem item = goods.get(i);
            line.append(item.id()).append(ChatColor.DARK_GRAY).append(" ")
                    .append(MarketService.money(item.mid())).append(ChatColor.GRAY).append("  ");
            if ((i + 1) % 4 == 0 || i == goods.size() - 1) {
                player.sendMessage(line.toString());
                line = new StringBuilder(ChatColor.GRAY.toString());
            }
        }
    }

    private void help(Player player) {
        player.sendMessage(ChatColor.GOLD + "===== /market =====");
        player.sendMessage(ChatColor.GRAY + " /market" + ChatColor.DARK_GRAY + " - 시장 화면을 엽니다");
        player.sendMessage(ChatColor.GRAY + " /market buy <품목> [개수]");
        player.sendMessage(ChatColor.GRAY + " /market sell <품목> [개수|all]");
        player.sendMessage(ChatColor.GRAY + " /market sellhand" + ChatColor.DARK_GRAY + " - 손에 든 것 팔기");
        player.sendMessage(ChatColor.GRAY + " /market price <품목>" + ChatColor.DARK_GRAY + " - 시세와 차트");
        player.sendMessage(ChatColor.GRAY + " /market top [down]" + ChatColor.DARK_GRAY + " - 급등/급락");
        player.sendMessage(ChatColor.GRAY + " /market list [분류]");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return prefixed(SUBS, args[0]);
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("list")) {
                List<String> ids = new ArrayList<>();
                for (var category : plugin.economyConfig().categories()) {
                    ids.add(category.id());
                }
                return prefixed(ids, args[1]);
            }
            if (sub.equals("buy") || sub.equals("sell") || sub.equals("sellall") || sub.equals("price")) {
                return prefixed(new ArrayList<>(plugin.market().items().keySet()), args[1]);
            }
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("sell")) {
            return prefixed(List.of("1", "16", "64", "all"), args[2]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("buy")) {
            return prefixed(List.of("1", "16", "64"), args[2]);
        }
        return List.of();
    }

    private static List<String> prefixed(List<String> options, String typed) {
        String prefix = typed.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String option : options) {
            if (option.startsWith(prefix)) {
                out.add(option);
            }
        }
        return out;
    }
}
