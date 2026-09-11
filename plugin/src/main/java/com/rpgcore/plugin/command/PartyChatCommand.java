package com.rpgcore.plugin.command;

import com.rpgcore.plugin.RpgCorePlugin;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /p &lt;message&gt; - party chat, which ignores the proximity range. */
public final class PartyChatCommand implements CommandExecutor {

    private final RpgCorePlugin plugin;

    public PartyChatCommand(RpgCorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("이 명령어는 플레이어만 사용할 수 있습니다.");
            return true;
        }
        if (!plugin.parties().enabled()) {
            player.sendMessage(ChatColor.RED + "[파티] 이 서버에서는 파티 기능이 꺼져 있습니다.");
            return true;
        }
        if (args.length == 0) {
            player.sendMessage(ChatColor.RED + "/p <메시지>");
            return true;
        }
        // Colour codes are stripped: the format is the operator's to control,
        // exactly as in proximity chat.
        plugin.parties().chat(player, ChatColor.stripColor(String.join(" ", args)));
        return true;
    }
}
