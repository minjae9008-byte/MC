package com.rpgcore.plugin.command;

import com.rpgcore.plugin.RpgCorePlugin;
import com.rpgcore.plugin.party.Party;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /party - create, invite, accept, leave, kick, disband, list. */
public final class PartyCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS =
            List.of("create", "name", "invite", "accept", "deny", "leave", "kick", "disband", "list");

    private final RpgCorePlugin plugin;

    public PartyCommand(RpgCorePlugin plugin) {
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
            usage(player);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "create" -> plugin.parties().create(player, join(args, 1));
            case "name", "rename" -> plugin.parties().rename(player, join(args, 1));
            case "invite" -> {
                if (args.length < 2) {
                    player.sendMessage(ChatColor.RED + "/party invite <플레이어>");
                    return true;
                }
                Player target = plugin.getServer().getPlayerExact(args[1]);
                if (target == null) {
                    player.sendMessage(ChatColor.RED + "[파티] 접속 중이 아닙니다: " + args[1]);
                    return true;
                }
                plugin.parties().invite(player, target);
            }
            case "accept" -> plugin.parties().accept(player, args.length > 1 ? args[1] : null);
            case "deny" -> plugin.parties().deny(player);
            case "leave" -> plugin.parties().leave(player);
            case "kick" -> {
                if (args.length < 2) {
                    player.sendMessage(ChatColor.RED + "/party kick <플레이어>");
                    return true;
                }
                plugin.parties().kick(player, args[1]);
            }
            case "disband" -> plugin.parties().disband(player);
            case "list" -> list(player);
            default -> usage(player);
        }
        return true;
    }

    /** Joins the remaining arguments so party names may contain spaces. */
    private String join(String[] args, int from) {
        return args.length <= from ? null : String.join(" ", java.util.Arrays.copyOfRange(args, from, args.length));
    }

    private void list(Player player) {
        Party party = plugin.parties().partyOf(player);
        if (party == null) {
            player.sendMessage(ChatColor.YELLOW + "[파티] 파티에 속해 있지 않습니다. /party create [이름] 으로 만드세요.");
            return;
        }
        player.sendMessage(ChatColor.GOLD + "===== " + ChatColor.WHITE + party.name() + ChatColor.GOLD
                + " (" + party.size() + "/" + plugin.rpgConfig().partyMaxSize() + ") =====");
        for (String line : plugin.parties().roster(party)) {
            player.sendMessage(line);
        }
        double range = plugin.rpgConfig().partyXpShareRange();
        player.sendMessage(ChatColor.GRAY + "경험치는 " + (range > 0 ? (int) range + "블록 안의 " : "같은 월드의 ")
                + "접속 중인 파티원끼리만 나눕니다.");
    }

    private void usage(Player player) {
        player.sendMessage(ChatColor.GOLD + "[파티] 사용법:");
        player.sendMessage(ChatColor.YELLOW + "  /party create [이름]" + ChatColor.GRAY + " - 파티 생성");
        player.sendMessage(ChatColor.YELLOW + "  /party name <이름>" + ChatColor.GRAY + " - 이름 변경 (파티장)");
        player.sendMessage(ChatColor.YELLOW + "  /party invite <플레이어>" + ChatColor.GRAY + " - 초대");
        player.sendMessage(ChatColor.YELLOW + "  /party accept [플레이어]" + ChatColor.GRAY + " - 초대 수락");
        player.sendMessage(ChatColor.YELLOW + "  /party deny" + ChatColor.GRAY + " - 초대 거절");
        player.sendMessage(ChatColor.YELLOW + "  /party list" + ChatColor.GRAY + " - 파티원 보기");
        player.sendMessage(ChatColor.YELLOW + "  /party kick <플레이어>" + ChatColor.GRAY + " - 추방 (파티장)");
        player.sendMessage(ChatColor.YELLOW + "  /party leave" + ChatColor.GRAY + " - 나가기");
        player.sendMessage(ChatColor.YELLOW + "  /party disband" + ChatColor.GRAY + " - 해체 (파티장)");
        player.sendMessage(ChatColor.YELLOW + "  /p <메시지>" + ChatColor.GRAY + " - 파티 채팅");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return prefixed(SUBS, args[0]);
        }
        if (args.length == 2 && sender instanceof Player player) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if ("invite".equals(sub)) {
                List<String> names = new ArrayList<>();
                for (Player online : plugin.getServer().getOnlinePlayers()) {
                    if (!online.equals(player)) {
                        names.add(online.getName());
                    }
                }
                return prefixed(names, args[1]);
            }
            if ("kick".equals(sub)) {
                Party party = plugin.parties().partyOf(player);
                if (party == null) {
                    return List.of();
                }
                List<String> names = new ArrayList<>(plugin.parties().memberNames(party));
                names.remove(player.getName());
                return prefixed(names, args[1]);
            }
        }
        return List.of();
    }

    private List<String> prefixed(List<String> options, String typed) {
        List<String> out = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(typed.toLowerCase(Locale.ROOT))) {
                out.add(option);
            }
        }
        return out;
    }
}
