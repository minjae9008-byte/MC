package com.rpgcore.plugin.command;

import com.rpgcore.plugin.RpgCorePlugin;
import com.rpgcore.plugin.guild.Guild;
import com.rpgcore.plugin.guild.GuildClaim;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /guild - everything a guild does that is not a click in a chest.
 */
public final class GuildCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of(
            "create", "invite", "accept", "deny", "leave", "kick", "transfer",
            "disband", "info", "list", "vault", "banner", "chat");

    private final RpgCorePlugin plugin;

    public GuildCommand(RpgCorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("이 명령어는 플레이어만 사용할 수 있습니다.");
            return true;
        }
        if (!plugin.guilds().enabled()) {
            player.sendMessage(ChatColor.RED + "[길드] 이 서버에서는 길드를 쓸 수 없습니다.");
            return true;
        }
        if (args.length == 0) {
            info(player);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "create", "창설", "생성" -> plugin.guilds().create(player, join(args, 1));
            case "invite", "초대" -> invite(player, args);
            case "accept", "수락" -> plugin.guilds().accept(player, args.length > 1 ? args[1] : null);
            case "deny", "거절" -> plugin.guilds().deny(player);
            case "leave", "탈퇴" -> plugin.guilds().leave(player);
            case "kick", "추방" -> withName(player, args, "/guild kick <플레이어>",
                    name -> plugin.guilds().kick(player, name));
            case "transfer", "양도" -> withName(player, args, "/guild transfer <플레이어>",
                    name -> plugin.guilds().transfer(player, name));
            case "disband", "해체" -> plugin.guilds().disband(player);
            case "vault", "보관함", "창고" -> plugin.guilds().openVault(player);
            case "banner", "깃발", "영지" -> banner(player);
            case "chat", "채팅" -> plugin.guilds().chat(player, join(args, 1));
            case "list", "목록" -> list(player);
            case "info", "정보" -> info(player);
            default -> help(player);
        }
        return true;
    }

    private void invite(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(ChatColor.YELLOW + "/guild invite <플레이어>");
            return;
        }
        Player target = plugin.getServer().getPlayerExact(args[1]);
        if (target == null) {
            player.sendMessage(ChatColor.RED + "[길드] 온라인이 아닌 플레이어입니다: " + args[1]);
            return;
        }
        plugin.guilds().invite(player, target);
    }

    private void withName(Player player, String[] args, String usage, java.util.function.Consumer<String> action) {
        if (args.length < 2) {
            player.sendMessage(ChatColor.YELLOW + usage);
            return;
        }
        action.accept(args[1]);
    }

    /**
     * Hands the leader a claim banner. Sold rather than given: a claim takes
     * land out of everyone else's reach, so it should cost the guild something
     * to take and something to take again.
     */
    private void banner(Player player) {
        Guild guild = plugin.guilds().guildOf(player);
        if (guild == null) {
            player.sendMessage(ChatColor.RED + "[길드] 길드에 속해 있지 않습니다.");
            return;
        }
        if (!guild.isLeader(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "[길드] 길드장만 영지 깃발을 받을 수 있습니다.");
            return;
        }
        int max = plugin.rpgConfig().guildMaxClaims();
        if (guild.claimCount() >= max) {
            player.sendMessage(ChatColor.RED + "[길드] 이미 영지가 " + max + "곳입니다. "
                    + "기존 깃발을 부수면 하나가 비워집니다.");
            return;
        }
        if (player.getInventory().firstEmpty() == -1) {
            player.sendMessage(ChatColor.RED + "[길드] 인벤토리에 빈 칸이 없습니다.");
            return;
        }
        int cost = plugin.rpgConfig().guildBannerCost();
        if (cost > 0 && !plugin.economy().take(player, cost)) {
            player.sendMessage(ChatColor.RED + "[길드] 깃발 값 " + cost + " 골드가 부족합니다. (보유 "
                    + plugin.economy().balance(player) + ")");
            return;
        }

        ItemStack banner = plugin.claimListener().claimBanner(null);
        player.getInventory().addItem(banner);
        player.sendMessage(ChatColor.GREEN + "[영지] 영지 깃발을 받았습니다."
                + (cost > 0 ? ChatColor.GRAY + " (-" + cost + " 골드)" : ""));
        player.sendMessage(ChatColor.GRAY + "  세우고 싶은 자리에 놓으면 반경 "
                + plugin.rpgConfig().guildClaimRadius() + "블록이 영지가 됩니다.");
    }

    private void info(Player player) {
        Guild guild = plugin.guilds().guildOf(player);
        if (guild == null) {
            player.sendMessage(ChatColor.GRAY + "[길드] 아직 길드가 없습니다. "
                    + ChatColor.YELLOW + "/guild create <이름>" + ChatColor.GRAY + " 으로 만드세요.");
            player.sendMessage(ChatColor.GRAY + "  창설 비용 " + plugin.rpgConfig().guildCreateCost() + " 골드.");
            return;
        }

        player.sendMessage(ChatColor.GOLD + "===== " + ChatColor.WHITE + guild.name()
                + ChatColor.GOLD + " =====");
        player.sendMessage(ChatColor.GRAY + "인원 " + ChatColor.WHITE + guild.size() + "/"
                + plugin.rpgConfig().guildMaxMembers()
                + ChatColor.GRAY + "   영지 " + ChatColor.WHITE + guild.claimCount() + "/"
                + plugin.rpgConfig().guildMaxClaims());
        for (String line : plugin.guilds().roster(guild)) {
            player.sendMessage("  " + line);
        }
        for (GuildClaim claim : guild.claims()) {
            player.sendMessage(ChatColor.GRAY + "  영지: " + ChatColor.WHITE + claim.describe());
        }
        player.sendMessage(ChatColor.GRAY + "  " + ChatColor.YELLOW + "/guild vault"
                + ChatColor.GRAY + " 보관함, " + ChatColor.YELLOW + "/guild chat <말>"
                + ChatColor.GRAY + " 길드 채팅");
    }

    private void list(Player player) {
        List<Guild> guilds = plugin.guilds().all();
        if (guilds.isEmpty()) {
            player.sendMessage(ChatColor.GRAY + "[길드] 아직 만들어진 길드가 없습니다.");
            return;
        }
        player.sendMessage(ChatColor.GOLD + "===== 길드 목록 (" + guilds.size() + ") =====");
        for (Guild guild : guilds) {
            player.sendMessage(ChatColor.WHITE + "  " + guild.name()
                    + ChatColor.GRAY + " - " + guild.size() + "명, 영지 " + guild.claimCount() + "곳"
                    + ChatColor.DARK_GRAY + " (장: " + guild.nameOf(guild.leader()) + ")");
        }
    }

    private void help(Player player) {
        player.sendMessage(ChatColor.GOLD + "===== 길드 =====");
        player.sendMessage(ChatColor.YELLOW + "/guild create <이름>" + ChatColor.GRAY + " - 창설 ("
                + plugin.rpgConfig().guildCreateCost() + " 골드)");
        player.sendMessage(ChatColor.YELLOW + "/guild invite|kick|transfer <플레이어>"
                + ChatColor.GRAY + " - 길드장 전용");
        player.sendMessage(ChatColor.YELLOW + "/guild accept|deny|leave|disband");
        player.sendMessage(ChatColor.YELLOW + "/guild vault" + ChatColor.GRAY + " - 공동 보관함");
        player.sendMessage(ChatColor.YELLOW + "/guild banner" + ChatColor.GRAY + " - 영지 깃발 ("
                + plugin.rpgConfig().guildBannerCost() + " 골드)");
        player.sendMessage(ChatColor.YELLOW + "/guild chat <말>" + ChatColor.GRAY + " - 길드 채팅");
        player.sendMessage(ChatColor.YELLOW + "/guild info|list");
    }

    private static String join(String[] args, int from) {
        if (args.length <= from) {
            return null;
        }
        return String.join(" ", java.util.Arrays.copyOfRange(args, from, args.length));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            for (String option : SUBCOMMANDS) {
                if (option.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    options.add(option);
                }
            }
            return options;
        }
        if (args.length == 2 && sender instanceof Player player) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            Guild guild = plugin.guilds().guildOf(player);
            if (guild != null && (sub.equals("kick") || sub.equals("transfer"))) {
                return plugin.guilds().memberNames(guild);
            }
        }
        return List.of();
    }
}
