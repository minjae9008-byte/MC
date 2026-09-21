package com.rpgcore.plugin.command;

import com.rpgcore.plugin.RpgCorePlugin;
import com.rpgcore.plugin.economy.BankAccount;
import com.rpgcore.plugin.economy.BankService;
import com.rpgcore.plugin.economy.EconomyConfig;
import com.rpgcore.plugin.economy.Loan;
import com.rpgcore.plugin.economy.TimeDeposit;
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
 * /bank - deposits, term savings, loans and what they cost.
 *
 * Every path that moves money prints the balance afterwards. A banking
 * command that answers "done" leaves the player to go and check, and a player
 * who has to go and check will eventually not bother and get a surprise.
 */
public final class BankCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = List.of(
            "info", "deposit", "withdraw", "save", "close", "loan", "repay", "rates", "help");

    private final RpgCorePlugin plugin;

    public BankCommand(RpgCorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("이 명령어는 플레이어만 사용할 수 있습니다.");
            return true;
        }
        if (!plugin.bank().enabled()) {
            player.sendMessage(ChatColor.RED + "[은행] 이 서버에서는 은행을 쓸 수 없습니다.");
            return true;
        }
        if (args.length == 0) {
            plugin.bankMenu().open(player);
            return true;
        }

        BankService bank = plugin.bank();
        BankAccount account = bank.account(player);
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "deposit", "입금" -> {
                long amount = amount(player, args, 1, plugin.economy().balance(player));
                if (amount > 0) {
                    bank.deposit(player, amount);
                }
            }
            case "withdraw", "출금" -> {
                long amount = amount(player, args, 1, account.checking());
                if (amount > 0) {
                    bank.withdraw(player, amount);
                }
            }
            case "save", "정기" -> {
                if (args.length < 2) {
                    player.sendMessage(ChatColor.YELLOW + "/bank save <금액|all> [일수]");
                    return true;
                }
                long amount = amount(player, args, 1, account.checking());
                int days = args.length > 2 ? parseInt(args[2], 7) : 7;
                if (amount > 0) {
                    bank.openTerm(player, amount, days);
                }
            }
            case "close", "해지" -> {
                int index = args.length > 1 ? parseInt(args[1], 1) : 1;
                bank.closeTerm(player, index - 1);
            }
            case "loan", "대출" -> {
                if (args.length < 2) {
                    player.sendMessage(ChatColor.YELLOW + "/bank loan <금액|max> [일수]");
                    player.sendMessage(ChatColor.GRAY + "  지금 빌릴 수 있는 돈: "
                            + bank.borrowable(account));
                    return true;
                }
                long amount = args[1].equalsIgnoreCase("max")
                        ? bank.borrowable(account)
                        : parseLong(args[1], 0);
                int days = args.length > 2 ? parseInt(args[2], 7) : 7;
                if (amount <= 0) {
                    player.sendMessage(ChatColor.RED + "[은행] 지금 빌릴 수 있는 돈이 없습니다.");
                    return true;
                }
                bank.borrow(player, amount, days);
            }
            case "repay", "상환" -> {
                long amount = amount(player, args, 1, account.totalDebt());
                if (amount > 0) {
                    bank.repay(player, amount);
                }
            }
            case "rates", "금리" -> rates(player);
            case "info", "정보" -> info(player, account);
            default -> help(player);
        }
        return true;
    }

    /** An amount, or "all" - which means something different per subcommand. */
    private long amount(Player player, String[] args, int index, long all) {
        if (args.length <= index) {
            player.sendMessage(ChatColor.YELLOW + "금액을 적어주세요. (all 도 됩니다)");
            return 0;
        }
        if (args[index].equalsIgnoreCase("all") || args[index].equals("전부")) {
            return all;
        }
        long value = parseLong(args[index], -1);
        if (value <= 0) {
            player.sendMessage(ChatColor.RED + "[은행] 숫자를 입력하세요: " + args[index]);
            return 0;
        }
        return value;
    }

    private static long parseLong(String text, long fallback) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int parseInt(String text, int fallback) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void info(Player player, BankAccount account) {
        BankService bank = plugin.bank();
        EconomyConfig.Grade grade = bank.grade(account);
        int day = plugin.macro().day();
        String symbol = plugin.rpgConfig().goldSymbol();
        player.sendMessage(ChatColor.GOLD + "===== " + player.getName() + " 님의 계좌 =====");
        player.sendMessage(ChatColor.WHITE + " 지갑 " + ChatColor.YELLOW
                + plugin.economy().balance(player) + symbol + ChatColor.GRAY + " · 입출금 "
                + ChatColor.YELLOW + account.checking() + symbol + ChatColor.GRAY + " · 정기 "
                + ChatColor.YELLOW + (account.totalDeposits() - account.checking()) + symbol);
        player.sendMessage(ChatColor.WHITE + " 채무 "
                + (account.totalDebt() > 0 ? ChatColor.RED : ChatColor.GRAY) + account.totalDebt()
                + symbol + ChatColor.GRAY + " · 순자산 " + ChatColor.WHITE
                + (plugin.economy().balance(player) + account.totalDeposits() - account.totalDebt()));
        player.sendMessage(ChatColor.WHITE + " 신용점수 " + ChatColor.YELLOW + account.creditScore()
                + ChatColor.GRAY + "/1000 · 등급 " + ChatColor.WHITE + grade.name()
                + ChatColor.GRAY + " · 한도 " + ChatColor.WHITE + bank.creditLimit(account)
                + ChatColor.GRAY + " · 여유 " + ChatColor.WHITE + bank.borrowable(account));
        if (!account.deposits().isEmpty()) {
            player.sendMessage(ChatColor.GRAY + " 정기예금:");
            int i = 1;
            for (TimeDeposit deposit : account.deposits()) {
                player.sendMessage(ChatColor.DARK_GRAY + "  " + i++ + ") " + deposit.principal()
                        + symbol + " · 연 " + BankService.percent(deposit.annualRate())
                        + " · 이자 " + String.format(Locale.ROOT, "%.0f", deposit.accrued())
                        + (deposit.matured(day) ? " · 만기됨" : " · " + deposit.daysLeft(day) + "일 남음"));
            }
            player.sendMessage(ChatColor.DARK_GRAY + "  /bank close <번호> 로 찾습니다.");
        }
        if (!account.loans().isEmpty()) {
            player.sendMessage(ChatColor.GRAY + " 대출:");
            int i = 1;
            for (Loan loan : account.loans()) {
                player.sendMessage(ChatColor.DARK_GRAY + "  " + i++ + ") 원금 " + loan.principal()
                        + " · 남은 " + loan.owed() + symbol + " · 연 "
                        + BankService.percent(loan.annualRate())
                        + (loan.overdue(day)
                        ? ChatColor.RED + " · 연체 " + loan.overdueDays() + "일"
                        : " · " + loan.daysLeft(day) + "일 남음"));
            }
        }
    }

    private void rates(Player player) {
        BankService bank = plugin.bank();
        EconomyConfig config = plugin.economyConfig();
        player.sendMessage(ChatColor.GOLD + "===== 금리표 =====");
        player.sendMessage(ChatColor.WHITE + " 정책금리 " + ChatColor.YELLOW
                + BankService.percent(bank.policyRate()) + ChatColor.GRAY
                + " (중앙은행이 물가를 보고 매일 정합니다)");
        player.sendMessage(ChatColor.WHITE + " 예금 " + ChatColor.YELLOW
                + BankService.percent(bank.depositRate()) + ChatColor.GRAY + " · 정기 7일 "
                + BankService.percent(bank.termRate(7)) + " · 30일 "
                + BankService.percent(bank.termRate(30)));
        for (EconomyConfig.Grade grade : config.grades()) {
            if (grade.limitMultiplier() <= 0) {
                player.sendMessage(ChatColor.DARK_GRAY + "  " + grade.name() + " ("
                        + grade.minScore() + "+) - 대출 불가");
                continue;
            }
            player.sendMessage(ChatColor.GRAY + "  " + grade.name() + " (" + grade.minScore()
                    + "+) 대출 " + BankService.percent(bank.policyRate()
                    + config.loanMarginPercent() + grade.riskPremium())
                    + " · 한도 배수 " + grade.limitMultiplier());
        }
        player.sendMessage(ChatColor.GRAY + " 이자는 경제일(" + config.dayMinutes()
                + "분)마다 붙고, 1년은 " + config.daysPerYear() + "일입니다.");
        player.sendMessage(ChatColor.GRAY + " 은행 대출 여력 " + bank.lendingCapacity()
                + " · 지급준비율 " + (int) config.reserveRatioPercent() + "%");
    }

    private void help(Player player) {
        player.sendMessage(ChatColor.GOLD + "===== /bank =====");
        player.sendMessage(ChatColor.GRAY + " /bank" + ChatColor.DARK_GRAY + " - 은행 화면을 엽니다");
        player.sendMessage(ChatColor.GRAY + " /bank info" + ChatColor.DARK_GRAY + " - 내 계좌와 신용등급");
        player.sendMessage(ChatColor.GRAY + " /bank deposit <금액|all>");
        player.sendMessage(ChatColor.GRAY + " /bank withdraw <금액|all>");
        player.sendMessage(ChatColor.GRAY + " /bank save <금액|all> [일수]"
                + ChatColor.DARK_GRAY + " - 정기예금");
        player.sendMessage(ChatColor.GRAY + " /bank close <번호>" + ChatColor.DARK_GRAY + " - 정기예금 해지");
        player.sendMessage(ChatColor.GRAY + " /bank loan <금액|max> [일수]");
        player.sendMessage(ChatColor.GRAY + " /bank repay <금액|all>");
        player.sendMessage(ChatColor.GRAY + " /bank rates" + ChatColor.DARK_GRAY + " - 금리표");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return prefixed(SUBS, args[0]);
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("deposit") || sub.equals("withdraw") || sub.equals("repay") || sub.equals("save")) {
                return prefixed(List.of("all", "1000", "10000"), args[1]);
            }
            if (sub.equals("loan")) {
                return prefixed(List.of("max", "1000", "10000"), args[1]);
            }
        }
        if (args.length == 3 && (args[0].equalsIgnoreCase("save") || args[0].equalsIgnoreCase("loan"))) {
            return prefixed(List.of("7", "14", "30"), args[2]);
        }
        return List.of();
    }

    private static List<String> prefixed(List<String> options, String typed) {
        String prefix = typed.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String option : options) {
            if (option.startsWith(prefix)) {
                out.add(option);
            }
        }
        return out;
    }
}
