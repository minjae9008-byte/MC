package com.rpgcore.plugin.command;

import com.rpgcore.plugin.RpgCorePlugin;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /collection - opens the collection log. */
public final class CollectionCommand implements CommandExecutor {

    private final RpgCorePlugin plugin;

    public CollectionCommand(RpgCorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("이 명령어는 플레이어만 사용할 수 있습니다.");
            return true;
        }
        if (!plugin.rpgConfig().collectionEnabled()) {
            player.sendMessage(ChatColor.RED + "[도감] 이 서버에서는 도감 기능이 꺼져 있습니다.");
            return true;
        }
        if (args.length > 0) {
            plugin.collectionMenu().openCategory(player, args[0]);
            return true;
        }
        plugin.collectionMenu().open(player);
        return true;
    }
}
