package com.rpgcore.plugin.command;

import com.rpgcore.plugin.RpgCorePlugin;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /pay &lt;player&gt; &lt;amount&gt; - hand gold to someone.
 *
 * Without this, gold only ever moves by winning a duel, which makes it a score
 * rather than a currency. The transfer is not counted as earned, so passing
 * the same coins back and forth cannot farm the "total earned" achievement.
 */
public final class PayCommand implements CommandExecutor, TabCompleter {

    private final RpgCorePlugin plugin;

    public PayCommand(RpgCorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("이 명령어는 플레이어만 사용할 수 있습니다. (콘솔은 /rpgcore givegold)");
            return true;
        }
        if (!plugin.rpgConfig().goldTransferAllowed()) {
            player.sendMessage(ChatColor.RED + "[골드] 이 서버에서는 골드를 주고받을 수 없습니다.");
            return true;
        }
        if (args.length < 2) {
            player.sendMessage(ChatColor.YELLOW + "/pay <플레이어> <금액>");
            return true;
        }

        Player target = plugin.getServer().getPlayerExact(args[0]);
        if (target == null) {
            player.sendMessage(ChatColor.RED + "[골드] 온라인이 아닌 플레이어입니다: " + args[0]);
            return true;
        }
        if (target.equals(player)) {
            player.sendMessage(ChatColor.RED + "[골드] 자기 자신에게는 보낼 수 없습니다.");
            return true;
        }

        int amount;
        try {
            amount = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            player.sendMessage(ChatColor.RED + "[골드] 숫자를 입력하세요: " + args[1]);
            return true;
        }
        if (amount <= 0) {
            player.sendMessage(ChatColor.RED + "[골드] 1 이상을 보내야 합니다.");
            return true;
        }
        if (!plugin.economy().take(player, amount)) {
            player.sendMessage(ChatColor.RED + "[골드] 골드가 부족합니다. (보유 "
                    + plugin.economy().balance(player) + ")");
            return true;
        }

        // Taken first, then given: a failure between the two would be a loss,
        // and refund() cannot fail once the sender has already paid.
        plugin.economy().refund(target, amount);
        player.sendMessage(ChatColor.GREEN + "[골드] " + target.getName() + " 에게 "
                + plugin.economy().format(amount) + ChatColor.GREEN + " 을(를) 보냈습니다. "
                + ChatColor.GRAY + "(남은 " + plugin.economy().balance(player) + ")");
        target.sendMessage(ChatColor.GREEN + "[골드] " + player.getName() + " 님이 "
                + plugin.economy().format(amount) + ChatColor.GREEN + " 을(를) 보냈습니다. "
                + ChatColor.GRAY + "(보유 " + plugin.economy().balance(target) + ")");
        target.playSound(target.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0F, 1.2F);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            if (!online.equals(sender) && online.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                out.add(online.getName());
            }
        }
        return out;
    }
}
