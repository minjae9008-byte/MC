package com.rpgcore.plugin.command;

import com.rpgcore.plugin.RpgCorePlugin;
import com.rpgcore.plugin.duel.DuelSession;
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
 * /duel &lt;player&gt; [wager] and the replies to it.
 */
public final class DuelCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("accept", "deny", "forfeit");

    private final RpgCorePlugin plugin;

    public DuelCommand(RpgCorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("이 명령어는 플레이어만 사용할 수 있습니다.");
            return true;
        }
        if (!plugin.duels().enabled()) {
            player.sendMessage(ChatColor.RED + "[대결] 이 서버에서는 대결 기능이 꺼져 있습니다.");
            return true;
        }
        if (args.length == 0) {
            usage(player);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "accept" -> plugin.duels().accept(player, args.length > 1 ? args[1] : null);
            case "deny", "decline" -> plugin.duels().deny(player);
            case "forfeit", "give-up", "포기" -> plugin.duels().forfeit(player);
            default -> {
                Player target = plugin.getServer().getPlayerExact(args[0]);
                if (target == null) {
                    player.sendMessage(ChatColor.RED + "[대결] 온라인이 아닌 플레이어입니다: " + args[0]);
                    return true;
                }
                plugin.duels().challenge(player, target, args.length > 1 ? args[1] : null);
            }
        }
        return true;
    }

    private void usage(Player player) {
        player.sendMessage(ChatColor.GOLD + "[대결] " + ChatColor.YELLOW + "/duel <플레이어> [건 것]");
        player.sendMessage(ChatColor.GRAY + "  건 것을 비우면 그냥 대결, 숫자면 골드, "
                + ChatColor.WHITE + "hand" + ChatColor.GRAY + " 면 손에 든 아이템입니다.");
        player.sendMessage(ChatColor.GRAY + "  양쪽이 같은 것을 걸고, 쓰러뜨린 쪽이 전부 가져갑니다. "
                + "죽지 않으므로 아이템은 떨어지지 않습니다.");
        player.sendMessage(ChatColor.YELLOW + "  /duel accept [플레이어]" + ChatColor.GRAY + " · "
                + ChatColor.YELLOW + "/duel deny" + ChatColor.GRAY + " · "
                + ChatColor.YELLOW + "/duel forfeit");
        DuelSession session = plugin.duels().sessionOf(player);
        if (session != null) {
            player.sendMessage(ChatColor.RED + "  지금 대결 중입니다. (" + session.ageSeconds() + "초 경과)");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (String sub : SUBCOMMANDS) {
                if (sub.startsWith(prefix)) {
                    out.add(sub);
                }
            }
            for (Player online : plugin.getServer().getOnlinePlayers()) {
                if (!online.equals(sender) && online.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    out.add(online.getName());
                }
            }
            return out;
        }
        if (args.length == 2 && !SUBCOMMANDS.contains(args[0].toLowerCase(Locale.ROOT))) {
            return List.of("hand", "10", "100");
        }
        return List.of();
    }
}
