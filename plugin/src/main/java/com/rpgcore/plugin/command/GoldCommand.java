package com.rpgcore.plugin.command;

import com.rpgcore.plugin.RpgCorePlugin;
import com.rpgcore.plugin.progress.CounterType;
import com.rpgcore.plugin.data.PlayerData;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /gold - your balance, or someone else's. */
public final class GoldCommand implements CommandExecutor {

    private final RpgCorePlugin plugin;

    public GoldCommand(RpgCorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0) {
            Player target = plugin.getServer().getPlayerExact(args[0]);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "[골드] 온라인이 아닌 플레이어입니다: " + args[0]);
                return true;
            }
            sender.sendMessage(ChatColor.GOLD + "[골드] " + ChatColor.WHITE + target.getName()
                    + ChatColor.GRAY + ": " + plugin.economy().format(plugin.economy().balance(target)));
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("/gold <player>");
            return true;
        }

        PlayerData data = plugin.players().get(player);
        sender.sendMessage(ChatColor.GOLD + "[골드] 보유: "
                + plugin.economy().format(data.gold())
                + ChatColor.DARK_GRAY + "  (누적 획득 " + data.counter(CounterType.GOLD_EARNED) + ")");
        return true;
    }
}
