package com.rpgcore.plugin.command;

import com.rpgcore.plugin.RpgCorePlugin;
import com.rpgcore.plugin.economy.MarketItem;
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
 * /economy - the dashboard, in a screen or in chat.
 *
 * The chat form matters more than it looks: it is the only one the console
 * and a command block can read, and it is what an operator checks when they
 * want to know whether the numbers on their server have gone anywhere strange.
 */
public final class EconomyCommand implements CommandExecutor, TabCompleter {

    private final RpgCorePlugin plugin;

    public EconomyCommand(RpgCorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player player) {
                plugin.economyMenu().open(player);
            } else {
                plugin.macro().print(sender);
            }
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "report", "지표" -> plugin.macro().print(sender);
            case "chart", "차트" -> chart(sender, args.length > 1 ? args[1] : null);
            case "day", "정산" -> {
                if (!sender.hasPermission("rpgcore.admin")) {
                    sender.sendMessage(ChatColor.RED + "권한이 없습니다.");
                    return true;
                }
                // Forcing a day is how an operator tests a rate change or a
                // shock without waiting twenty minutes for one.
                plugin.macro().advanceDay();
                sender.sendMessage(ChatColor.GREEN + "[경제] 경제일을 하루 넘겼습니다. 지금 "
                        + plugin.macro().day() + "일차.");
            }
            default -> {
                sender.sendMessage(ChatColor.GOLD + "===== /economy =====");
                sender.sendMessage(ChatColor.GRAY + " /economy" + ChatColor.DARK_GRAY + " - 지표판을 엽니다");
                sender.sendMessage(ChatColor.GRAY + " /economy report" + ChatColor.DARK_GRAY + " - 채팅으로 요약");
                sender.sendMessage(ChatColor.GRAY + " /economy chart <품목>" + ChatColor.DARK_GRAY + " - 가격 추이");
                if (sender.hasPermission("rpgcore.admin")) {
                    sender.sendMessage(ChatColor.GRAY + " /economy day" + ChatColor.DARK_GRAY
                            + " - (관리자) 경제일을 하루 넘깁니다");
                }
            }
        }
        return true;
    }

    private void chart(CommandSender sender, String id) {
        if (id == null) {
            sender.sendMessage(ChatColor.RED + "[경제] 품목을 적어주세요. 예: /economy chart diamond");
            return;
        }
        MarketItem item = plugin.market().byId(id);
        if (item == null) {
            sender.sendMessage(ChatColor.RED + "[경제] 그런 품목이 없습니다: " + id);
            return;
        }
        List<Double> history = item.history();
        if (history.size() < 2) {
            sender.sendMessage(ChatColor.GRAY + "[경제] 아직 기록이 없습니다. 하루가 지나야 한 점이 찍힙니다.");
            return;
        }
        double low = history.get(0);
        double high = history.get(0);
        for (double point : history) {
            low = Math.min(low, point);
            high = Math.max(high, point);
        }
        sender.sendMessage(ChatColor.GOLD + "===== " + item.id() + " 가격 추이 ("
                + history.size() + "일) =====");
        sender.sendMessage(ChatColor.AQUA + " " + item.sparkline());
        sender.sendMessage(ChatColor.GRAY + " 최저 " + MarketService.money(low)
                + " · 최고 " + MarketService.money(high)
                + " · 지금 " + ChatColor.WHITE + MarketService.money(item.mid())
                + ChatColor.GRAY + " " + MarketService.change(item.changePercent()));
        sender.sendMessage(ChatColor.GRAY + " 기준가 " + MarketService.money(item.basePrice())
                + " · 재고 " + String.format(Locale.ROOT, "%,.0f", item.stock())
                + " (" + String.format(Locale.ROOT, "%.0f%%", item.supplyPercent()) + ")");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(List.of("report", "chart"));
            if (sender.hasPermission("rpgcore.admin")) {
                subs.add("day");
            }
            return prefixed(subs, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("chart")) {
            return prefixed(new ArrayList<>(plugin.market().items().keySet()), args[1]);
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
