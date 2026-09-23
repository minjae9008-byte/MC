package com.rpgcore.plugin.command;

import com.rpgcore.plugin.RpgCorePlugin;
import com.rpgcore.plugin.corp.Company;
import com.rpgcore.plugin.corp.CorpService;
import com.rpgcore.plugin.economy.MarketItem;
import com.rpgcore.plugin.economy.MarketService;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * /stocks - invest in somebody else's company.
 *
 * Buying here is not a wager against another player: the shares come out of
 * the company's own treasury and the gold goes into its account, where it
 * pays for the next factory. That is what makes an investment an investment
 * rather than a bet, and it is why the screen leads with what the business
 * earned rather than with the price chart.
 */
public final class StockCommand implements CommandExecutor, TabCompleter {

    private final RpgCorePlugin plugin;

    public StockCommand(RpgCorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("이 명령어는 플레이어만 사용할 수 있습니다.");
            return true;
        }
        if (!plugin.corps().enabled()) {
            player.sendMessage(ChatColor.RED + "[주식] 이 서버에서는 기업 기능을 쓸 수 없습니다.");
            return true;
        }
        CorpService corps = plugin.corps();
        if (args.length == 0) {
            plugin.stockMenu().open(player);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "buy", "매수" -> {
                Company company = require(player, args.length > 1 ? args[1] : null);
                if (company != null) {
                    corps.buyShares(player, company, count(args, 2, 10));
                }
            }
            case "sell", "매도" -> {
                Company company = require(player, args.length > 1 ? args[1] : null);
                if (company == null) {
                    return true;
                }
                long units = args.length > 2 && args[2].equalsIgnoreCase("all")
                        ? company.sharesOf(player.getUniqueId())
                        : count(args, 2, 10);
                corps.sellShares(player, company, units);
            }
            case "info", "정보" -> {
                Company company = require(player, args.length > 1 ? args[1] : null);
                if (company != null) {
                    info(player, company);
                }
            }
            case "portfolio", "보유" -> portfolio(player);
            default -> help(player);
        }
        return true;
    }

    private Company require(Player player, String text) {
        if (text == null) {
            player.sendMessage(ChatColor.RED + "[주식] 회사 이름이나 티커를 적어주세요.");
            return null;
        }
        Company company = plugin.corps().find(text);
        if (company == null) {
            player.sendMessage(ChatColor.RED + "[주식] 그런 회사가 없습니다: " + text);
        }
        return company;
    }

    private static long count(String[] args, int index, long fallback) {
        if (args.length <= index) {
            return fallback;
        }
        try {
            return Math.max(1, Long.parseLong(args[index].replace(",", "")));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void info(Player player, Company company) {
        CorpService corps = plugin.corps();
        String symbol = plugin.rpgConfig().goldSymbol();
        player.sendMessage(ChatColor.GOLD + "===== " + company.ticker() + " · "
                + company.name() + " =====");
        player.sendMessage(ChatColor.WHITE + " 살 때 " + ChatColor.GOLD
                + MarketService.money(corps.askPrice(company)) + symbol
                + ChatColor.DARK_GRAY + "  /  " + ChatColor.WHITE + "팔 때 " + ChatColor.YELLOW
                + MarketService.money(corps.bidPrice(company)) + symbol);
        String chart = MarketItem.spark(company.priceHistory());
        if (!chart.isEmpty()) {
            player.sendMessage(ChatColor.AQUA + " " + chart);
        }
        player.sendMessage(ChatColor.GRAY + " 기업가치 " + comma(Math.round(corps.fairValue(company)))
                + " = 장부 " + comma(Math.round(corps.bookValue(company)))
                + " 와 수익가치의 가중평균");
        player.sendMessage(ChatColor.GRAY + " 어제 이익 " + comma(company.lastProfit())
                + " · 배당수익률 " + String.format(Locale.ROOT, "%.1f%%",
                corps.dividendYieldPercent(company))
                + " · PER " + (corps.priceEarnings(company) <= 0 ? "-"
                : String.format(Locale.ROOT, "%.1f", corps.priceEarnings(company))));
        player.sendMessage(ChatColor.GRAY + " 발행 " + comma(company.sharesIssued())
                + "주 · 살 수 있는 물량 " + comma(company.treasuryShares()) + "주");
        player.sendMessage(ChatColor.GRAY + " 공장 " + company.factories().size()
                + "개 · 현금 " + comma(company.cash())
                + " · 직원 " + company.headcount() + "명");
        long mine = company.sharesOf(player.getUniqueId());
        if (mine > 0) {
            player.sendMessage(ChatColor.GREEN + " 내 보유 " + comma(mine) + "주 · 평가 "
                    + comma(Math.round(mine * corps.bidPrice(company))));
        }
    }

    private void portfolio(Player player) {
        CorpService corps = plugin.corps();
        Map<Company, Long> holdings = corps.portfolioOf(player.getUniqueId());
        player.sendMessage(ChatColor.GOLD + "===== 내 포트폴리오 =====");
        if (holdings.isEmpty()) {
            player.sendMessage(ChatColor.GRAY + " 가진 주식이 없습니다. /stocks 로 둘러보세요.");
            return;
        }
        long total = 0;
        for (Map.Entry<Company, Long> entry : holdings.entrySet()) {
            Company company = entry.getKey();
            long worth = Math.round(corps.bidPrice(company) * entry.getValue());
            total += worth;
            player.sendMessage(ChatColor.GRAY + " " + company.ticker() + " "
                    + comma(entry.getValue()) + "주 · 평가 " + ChatColor.WHITE + comma(worth)
                    + ChatColor.GRAY + " · 지분 " + String.format(Locale.ROOT, "%.1f%%",
                    entry.getValue() * 100.0 / Math.max(1, company.sharesIssued())));
        }
        player.sendMessage(ChatColor.WHITE + " 평가액 합계 " + ChatColor.GOLD + comma(total));
    }

    private void help(Player player) {
        player.sendMessage(ChatColor.GOLD + "===== /stocks =====");
        player.sendMessage(ChatColor.GRAY + " /stocks" + ChatColor.DARK_GRAY + " - 거래소 화면");
        player.sendMessage(ChatColor.GRAY + " /stocks buy <티커> [주식수]");
        player.sendMessage(ChatColor.GRAY + " /stocks sell <티커> [주식수|all]");
        player.sendMessage(ChatColor.GRAY + " /stocks info <티커>");
        player.sendMessage(ChatColor.GRAY + " /stocks portfolio");
    }

    private static String comma(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return prefixed(List.of("buy", "sell", "info", "portfolio"), args[0]);
        }
        if (args.length == 2) {
            List<String> tickers = new ArrayList<>();
            for (Company company : plugin.corps().all()) {
                tickers.add(company.ticker());
            }
            return prefixed(tickers, args[1]);
        }
        if (args.length == 3) {
            return prefixed(List.of("10", "100", "1000", "all"), args[2]);
        }
        return List.of();
    }

    private static List<String> prefixed(List<String> options, String typed) {
        String prefix = typed.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                out.add(option);
            }
        }
        return out;
    }
}
