package com.rpgcore.plugin.command;

import com.rpgcore.plugin.RpgCorePlugin;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /trade &lt;player|accept|deny|cancel&gt; */
public final class TradeCommand implements CommandExecutor, TabCompleter {

    private final RpgCorePlugin plugin;

    public TradeCommand(RpgCorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("이 명령어는 플레이어만 사용할 수 있습니다.");
            return true;
        }
        if (!plugin.trades().enabled()) {
            player.sendMessage(ChatColor.RED + "[거래] 이 서버에서는 거래 기능이 꺼져 있습니다.");
            return true;
        }
        if (args.length == 0) {
            usage(player);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "accept" -> plugin.trades().accept(player, args.length > 1 ? args[1] : null);
            case "deny" -> plugin.trades().deny(player);
            case "cancel" -> plugin.trades().cancel(player, "직접 취소했습니다.");
            default -> {
                Player target = plugin.getServer().getPlayerExact(args[0]);
                if (target == null) {
                    player.sendMessage(ChatColor.RED + "[거래] 접속 중이 아닙니다: " + args[0]);
                    return true;
                }
                plugin.trades().request(player, target);
            }
        }
        return true;
    }

    private void usage(Player player) {
        player.sendMessage(ChatColor.GOLD + "[거래] 사용법:");
        player.sendMessage(ChatColor.YELLOW + "  /trade <플레이어>" + ChatColor.GRAY + " - 거래 요청");
        player.sendMessage(ChatColor.YELLOW + "  /trade accept [플레이어]" + ChatColor.GRAY + " - 요청 수락");
        player.sendMessage(ChatColor.YELLOW + "  /trade deny" + ChatColor.GRAY + " - 요청 거절");
        player.sendMessage(ChatColor.YELLOW + "  /trade cancel" + ChatColor.GRAY + " - 진행 중인 거래 취소");
        player.sendMessage(ChatColor.GRAY + "왼쪽 4칸이 내 물건, 오른쪽 4칸이 상대 물건입니다. "
                + "양쪽이 확정 버튼을 누르면 교환됩니다.");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1 || !(sender instanceof Player player)) {
            return List.of();
        }
        List<String> options = new ArrayList<>(List.of("accept", "deny", "cancel"));
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            if (!online.equals(player)) {
                options.add(online.getName());
            }
        }
        List<String> out = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT))) {
                out.add(option);
            }
        }
        return out;
    }
}
