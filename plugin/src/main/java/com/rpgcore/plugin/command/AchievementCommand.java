package com.rpgcore.plugin.command;

import com.rpgcore.plugin.RpgCorePlugin;
import com.rpgcore.plugin.data.PlayerData;
import com.rpgcore.plugin.progress.Achievement;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Set;

/**
 * /achievements - what there is to chase and how far along you are.
 *
 * A plain chat list rather than a GUI: it is a progress report to read, and
 * chat is the one surface that scrolls, so a long list stays usable.
 */
public final class AchievementCommand implements CommandExecutor {

    private final RpgCorePlugin plugin;

    public AchievementCommand(RpgCorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("이 명령어는 플레이어만 사용할 수 있습니다.");
            return true;
        }
        if (!plugin.rpgConfig().achievementsEnabled()) {
            player.sendMessage(ChatColor.RED + "[업적] 이 서버에서는 업적 기능이 꺼져 있습니다.");
            return true;
        }
        if (plugin.achievements().isEmpty()) {
            player.sendMessage(ChatColor.YELLOW + "[업적] 등록된 업적이 없습니다.");
            return true;
        }

        PlayerData data = plugin.players().get(player);
        Set<String> earned = plugin.achievements().earned(player);
        player.sendMessage(ChatColor.GOLD + "===== 업적 " + ChatColor.WHITE + earned.size()
                + ChatColor.GRAY + "/" + plugin.achievements().count() + ChatColor.GOLD + " =====");

        for (Achievement achievement : plugin.achievements().all()) {
            boolean done = earned.contains(achievement.id());
            int have = Math.min(data.counter(achievement.counter()), achievement.goal());
            String progress = done
                    ? ChatColor.GREEN + "✔"
                    : ChatColor.GRAY + "" + have + "/" + achievement.goal();

            String line = (done ? ChatColor.GREEN : ChatColor.WHITE) + achievement.display()
                    + ChatColor.DARK_GRAY + " · " + progress;
            if (achievement.firstOnly()) {
                String holder = plugin.achievements().firstHolder(achievement);
                // A taken first place is worth showing: it turns a goal nobody
                // can reach any more into a record with a name on it.
                line += ChatColor.DARK_GRAY + " · " + (holder == null
                        ? ChatColor.GOLD + "서버 최초 미달성"
                        : ChatColor.GRAY + "최초: " + holder);
            }
            player.sendMessage("  " + line);
        }
        player.sendMessage(ChatColor.GRAY + "칭호는 " + ChatColor.YELLOW + "/titles"
                + ChatColor.GRAY + " 에서 고를 수 있습니다.");
        return true;
    }
}
