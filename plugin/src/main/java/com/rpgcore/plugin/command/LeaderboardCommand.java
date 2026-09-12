package com.rpgcore.plugin.command;

import com.rpgcore.plugin.RpgCorePlugin;
import com.rpgcore.plugin.leaderboard.LeaderboardService;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /leaderboard [category] - top players, plus where the viewer sits. */
public final class LeaderboardCommand implements CommandExecutor, TabCompleter {

    private static final ChatColor[] MEDALS = {ChatColor.GOLD, ChatColor.WHITE, ChatColor.DARK_RED};

    private final RpgCorePlugin plugin;

    public LeaderboardCommand(RpgCorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!plugin.rpgConfig().leaderboardEnabled()) {
            sender.sendMessage(ChatColor.RED + "[순위] 이 서버에서는 순위표가 꺼져 있습니다.");
            return true;
        }
        String category = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "level";
        if (!plugin.leaderboard().isCategory(category)) {
            sender.sendMessage(ChatColor.RED + "[순위] 알 수 없는 항목: " + args[0]
                    + ChatColor.GRAY + " (" + String.join(", ", plugin.leaderboard().categories()) + ")");
            return true;
        }

        List<LeaderboardService.Row> rows = plugin.leaderboard().ranking(category);
        if (rows.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "[순위] 아직 기록이 없습니다.");
            return true;
        }

        int size = Math.min(plugin.rpgConfig().leaderboardSize(), rows.size());
        sender.sendMessage(ChatColor.GOLD + "===== 순위: " + ChatColor.YELLOW + label(category)
                + ChatColor.GOLD + " =====");
        String self = sender instanceof Player player ? player.getName() : null;
        for (int i = 0; i < size; i++) {
            sender.sendMessage(format(rows.get(i), category, rows.get(i).name().equalsIgnoreCase(self)));
        }

        if (self != null) {
            LeaderboardService.Row mine = plugin.leaderboard().rankOf(self, category);
            if (mine == null) {
                sender.sendMessage(ChatColor.GRAY + "당신의 기록은 아직 없습니다.");
            } else if (mine.rank() > size) {
                sender.sendMessage(ChatColor.GRAY + "...");
                sender.sendMessage(format(mine, category, true));
            }
        }
        if (args.length == 0) {
            // Only when they did not name one: the list is long now that every
            // tally is rankable, and it would be noise on a repeat call.
            sender.sendMessage(ChatColor.DARK_GRAY + "다른 항목: "
                    + String.join(", ", plugin.leaderboard().categories()));
        }
        return true;
    }

    private String format(LeaderboardService.Row row, String category, boolean self) {
        ChatColor rankColor = row.rank() <= MEDALS.length ? MEDALS[row.rank() - 1] : ChatColor.GRAY;
        String value = "level".equals(category)
                ? "Lv." + row.level() + ChatColor.DARK_GRAY + " (XP " + row.xp() + ")"
                : label(category) + " " + row.value() + ChatColor.DARK_GRAY + " (Lv." + row.level() + ")";
        return rankColor + "#" + row.rank() + " "
                + (self ? ChatColor.GREEN + "" + ChatColor.BOLD : ChatColor.WHITE) + row.name()
                + ChatColor.GRAY + " - " + ChatColor.AQUA + value;
    }

    private String label(String category) {
        return plugin.leaderboard().label(category);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String category : plugin.leaderboard().categories()) {
            if (category.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                out.add(category);
            }
        }
        return out;
    }
}
