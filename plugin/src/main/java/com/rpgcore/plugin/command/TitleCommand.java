package com.rpgcore.plugin.command;

import com.rpgcore.plugin.RpgCorePlugin;
import com.rpgcore.plugin.progress.TitleService;
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
 * /titles - opens the picker, or sets one straight from chat.
 */
public final class TitleCommand implements CommandExecutor, TabCompleter {

    private final RpgCorePlugin plugin;

    public TitleCommand(RpgCorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("이 명령어는 플레이어만 사용할 수 있습니다.");
            return true;
        }
        if (!plugin.rpgConfig().achievementsEnabled() && !plugin.rpgConfig().collectionEnabled()) {
            player.sendMessage(ChatColor.RED + "[칭호] 이 서버에서는 칭호 기능이 꺼져 있습니다.");
            return true;
        }
        if (args.length == 0) {
            plugin.titleMenu().open(player);
            return true;
        }
        if (args[0].equalsIgnoreCase("off") || args[0].equals("해제")) {
            plugin.titles().wear(player, null);
            player.sendMessage(ChatColor.YELLOW + "[칭호] 칭호를 뗐습니다.");
            return true;
        }

        TitleService.Title title = plugin.titles().resolve(String.join(" ", args));
        if (title == null) {
            player.sendMessage(ChatColor.RED + "[칭호] 그런 칭호가 없습니다: " + args[0]);
            return true;
        }
        if (!plugin.titles().hasEarned(player, title.id())) {
            player.sendMessage(ChatColor.RED + "[칭호] 아직 얻지 못한 칭호입니다. "
                    + ChatColor.GRAY + "(조건: " + title.requirement() + ")");
            return true;
        }
        plugin.titles().wear(player, title.id());
        player.sendMessage(ChatColor.GREEN + "[칭호] " + title.display() + ChatColor.GREEN + " 을(를) 착용했습니다.");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1 || !(sender instanceof Player player)) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        if ("off".startsWith(prefix)) {
            out.add("off");
        }
        // Only what they can actually wear: offering locked ids would just
        // hand back a refusal.
        for (String id : plugin.titles().earned(player)) {
            if (id.startsWith(prefix)) {
                out.add(id);
            }
        }
        return out;
    }
}
