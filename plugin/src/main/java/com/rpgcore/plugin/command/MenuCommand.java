package com.rpgcore.plugin.command;

import com.rpgcore.plugin.RpgCorePlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /menu - the one command a new player needs to be told about.
 */
public final class MenuCommand implements CommandExecutor {

    private final RpgCorePlugin plugin;

    public MenuCommand(RpgCorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("이 명령어는 플레이어만 사용할 수 있습니다.");
            return true;
        }
        plugin.mainMenu().open(player);
        return true;
    }
}
