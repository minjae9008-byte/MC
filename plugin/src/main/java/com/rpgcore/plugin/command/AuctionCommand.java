package com.rpgcore.plugin.command;

import com.rpgcore.plugin.RpgCorePlugin;
import com.rpgcore.plugin.auction.AuctionListing;
import com.rpgcore.plugin.auction.AuctionMenu;
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
 * /auction - browse, sell, and collect.
 *
 * Browsing and buying are the GUI's job; this exists for the two things a
 * chest screen cannot express - naming a price, and being usable when a
 * player already knows what they want.
 */
public final class AuctionCommand implements CommandExecutor, TabCompleter {

    private final RpgCorePlugin plugin;

    public AuctionCommand(RpgCorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("이 명령어는 플레이어만 사용할 수 있습니다.");
            return true;
        }
        if (!plugin.auctions().enabled()) {
            player.sendMessage(ChatColor.RED + "[경매] 이 서버에서는 경매장을 쓸 수 없습니다.");
            return true;
        }
        if (args.length == 0) {
            plugin.auctionMenu().open(player);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "sell", "판매", "등록" -> sell(player, args);
            case "mine", "내물건", "목록" -> plugin.auctionMenu().open(player, AuctionMenu.View.MINE, 0);
            case "collect", "받기", "우편" -> collect(player);
            case "help", "도움말" -> help(player);
            default -> help(player);
        }
        return true;
    }

    private void sell(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(ChatColor.YELLOW + "/auction sell <시작가> [즉시구매가]");
            player.sendMessage(ChatColor.GRAY + "  손에 든 물건을 올립니다. 즉시구매가는 생략할 수 있습니다.");
            return;
        }
        int start = parse(player, args[1], "시작가");
        if (start < 0) {
            return;
        }
        int buyNow = 0;
        if (args.length >= 3) {
            buyNow = parse(player, args[2], "즉시구매가");
            if (buyNow < 0) {
                return;
            }
        }
        plugin.auctions().sell(player, start, buyNow);
    }

    /** -1 after telling the player why; never a valid price. */
    private int parse(Player player, String raw, String what) {
        try {
            int value = Integer.parseInt(raw);
            if (value <= 0) {
                player.sendMessage(ChatColor.RED + "[경매] " + what + "는 1 골드 이상이어야 합니다.");
                return -1;
            }
            return value;
        } catch (NumberFormatException e) {
            player.sendMessage(ChatColor.RED + "[경매] " + what + "는 숫자로 적어 주세요: " + raw);
            return -1;
        }
    }

    private void collect(Player player) {
        if (plugin.mailbox().pending(player.getUniqueId()) == 0) {
            player.sendMessage(ChatColor.GRAY + "[우편] 받을 것이 없습니다.");
            return;
        }
        plugin.mailbox().collect(player);
    }

    private void help(Player player) {
        player.sendMessage(ChatColor.GOLD + "===== 경매장 =====");
        player.sendMessage(ChatColor.YELLOW + "/auction" + ChatColor.GRAY + " - 경매장을 엽니다");
        player.sendMessage(ChatColor.YELLOW + "/auction sell <시작가> [즉시구매가]"
                + ChatColor.GRAY + " - 손에 든 물건을 올립니다");
        player.sendMessage(ChatColor.YELLOW + "/auction mine" + ChatColor.GRAY + " - 내가 올린 물건");
        player.sendMessage(ChatColor.YELLOW + "/auction collect" + ChatColor.GRAY + " - 우편함에서 받기");
        player.sendMessage(ChatColor.GRAY + "  등록 수수료 " + plugin.rpgConfig().auctionListingFeePercent()
                + "%, 판매 수수료 " + plugin.rpgConfig().auctionTaxPercent()
                + "%, 기본 " + (plugin.rpgConfig().auctionDurationMinutes() / 60) + "시간 동안 진행됩니다.");
        List<AuctionListing> mine = plugin.auctions().listingsOf(player.getUniqueId());
        if (!mine.isEmpty()) {
            player.sendMessage(ChatColor.GRAY + "  올려 둔 물건 " + mine.size() + "개가 진행 중입니다.");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            for (String option : List.of("sell", "mine", "collect", "help")) {
                if (option.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    options.add(option);
                }
            }
            return options;
        }
        return List.of();
    }
}
