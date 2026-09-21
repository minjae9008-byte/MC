package com.rpgcore.plugin.economy;

import com.rpgcore.plugin.RpgCorePlugin;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The bank counter.
 *
 * Amounts are chosen by which mouse button is used rather than typed, because
 * a chest GUI has nowhere to type and Bedrock players cannot be handed a text
 * box. Every button says in its own tooltip exactly what each click does and
 * what it will cost, so nothing here is a surprise - this screen moves real
 * gold, and a mis-click that takes out a thirty-day loan would be unforgivable.
 */
public final class BankMenu {

    /** What a button does. */
    public enum Action {
        DEPOSIT, WITHDRAW, TERM, BORROW, REPAY, RATES, HEALTH,
        BACK, MARKET, DASHBOARD, CLOSE
    }

    private static final int SIZE = 54;

    public static final class Holder implements InventoryHolder {
        private Inventory inventory;
        private final Map<Integer, Action> actions = new HashMap<>();
        private final Map<Integer, Integer> deposits = new HashMap<>();
        private final Map<Integer, Integer> loans = new HashMap<>();

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        public Action actionAt(int slot) {
            return actions.get(slot);
        }

        /** The index of the term deposit drawn in this slot, or null. */
        public Integer depositAt(int slot) {
            return deposits.get(slot);
        }

        public Integer loanAt(int slot) {
            return loans.get(slot);
        }
    }

    private final RpgCorePlugin plugin;

    public BankMenu(RpgCorePlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        if (!plugin.bank().enabled()) {
            player.sendMessage(ChatColor.RED + "[은행] 이 서버에서는 은행을 쓸 수 없습니다.");
            return;
        }
        BankService bank = plugin.bank();
        EconomyConfig config = plugin.economyConfig();
        BankAccount account = bank.account(player);

        Holder holder = new Holder();
        Inventory inv = plugin.getServer().createInventory(holder, SIZE,
                ChatColor.translateAlternateColorCodes('&', config.bankTitle()));
        holder.setInventory(inv);
        for (int slot = 0; slot < SIZE; slot++) {
            inv.setItem(slot, filler());
        }

        inv.setItem(4, accountCard(player, account));

        button(inv, holder, 10, Action.DEPOSIT, Material.GOLD_INGOT, "&a입금", List.of(
                ChatColor.GRAY + "지갑의 골드를 계좌에 넣습니다.",
                ChatColor.GRAY + "예금은 이자가 붙고, 대출 한도의 바탕이 됩니다.",
                "",
                ChatColor.YELLOW + "좌클릭" + ChatColor.GRAY + " 100 · "
                        + ChatColor.YELLOW + "Shift+좌클릭" + ChatColor.GRAY + " 1,000",
                ChatColor.YELLOW + "우클릭" + ChatColor.GRAY + " 10,000 · "
                        + ChatColor.YELLOW + "Shift+우클릭" + ChatColor.GRAY + " 전액"));
        button(inv, holder, 11, Action.WITHDRAW, Material.GOLD_NUGGET, "&e출금", List.of(
                ChatColor.GRAY + "계좌에서 지갑으로 뺍니다.",
                ChatColor.GRAY + "잔액 " + ChatColor.WHITE + account.checking(),
                "",
                ChatColor.YELLOW + "좌클릭" + ChatColor.GRAY + " 100 · "
                        + ChatColor.YELLOW + "Shift+좌클릭" + ChatColor.GRAY + " 1,000",
                ChatColor.YELLOW + "우클릭" + ChatColor.GRAY + " 10,000 · "
                        + ChatColor.YELLOW + "Shift+우클릭" + ChatColor.GRAY + " 전액"));
        button(inv, holder, 12, Action.TERM, Material.ENDER_CHEST, "&b정기예금 가입", List.of(
                ChatColor.GRAY + "일정 기간 묶어두고 더 높은 이자를 받습니다.",
                ChatColor.GRAY + "7일 금리 " + ChatColor.WHITE + BankService.percent(bank.termRate(7))
                        + ChatColor.GRAY + " · 30일 금리 " + ChatColor.WHITE
                        + BankService.percent(bank.termRate(30)),
                ChatColor.GRAY + "중도 해지 시 이자의 "
                        + (int) config.earlyWithdrawalPenaltyPercent() + "% 를 못 받습니다.",
                "",
                ChatColor.YELLOW + "좌클릭" + ChatColor.GRAY + " 1,000 · 7일",
                ChatColor.YELLOW + "Shift+좌클릭" + ChatColor.GRAY + " 10,000 · 7일",
                ChatColor.YELLOW + "우클릭" + ChatColor.GRAY + " 10,000 · 30일",
                ChatColor.YELLOW + "Shift+우클릭" + ChatColor.GRAY + " 전액 · 30일"));
        button(inv, holder, 14, Action.BORROW, Material.WRITABLE_BOOK, "&6대출 받기", List.of(
                ChatColor.GRAY + "신용등급 " + ChatColor.WHITE + bank.grade(account).name()
                        + ChatColor.GRAY + " · 적용 금리 " + ChatColor.WHITE
                        + BankService.percent(bank.loanRate(account)),
                ChatColor.GRAY + "한도 " + ChatColor.WHITE + bank.creditLimit(account)
                        + ChatColor.GRAY + " · 지금 빌릴 수 있는 돈 " + ChatColor.WHITE
                        + bank.borrowable(account),
                ChatColor.GRAY + "취급 수수료 " + trimPercent(config.originationFeePercent())
                        + " · 연체하면 가산금리 " + (int) config.overdueExtraPercent() + "%",
                "",
                ChatColor.YELLOW + "좌클릭" + ChatColor.GRAY + " 한도의 1/4 · 7일",
                ChatColor.YELLOW + "Shift+좌클릭" + ChatColor.GRAY + " 한도 전액 · 7일",
                ChatColor.YELLOW + "우클릭" + ChatColor.GRAY + " 한도의 1/4 · 30일",
                ChatColor.YELLOW + "Shift+우클릭" + ChatColor.GRAY + " 한도 전액 · 30일"));
        button(inv, holder, 15, Action.REPAY, Material.EMERALD, "&a상환", List.of(
                ChatColor.GRAY + "총 채무 " + ChatColor.RED + account.totalDebt(),
                ChatColor.GRAY + "오래된 대출부터 갚습니다.",
                "",
                ChatColor.YELLOW + "좌클릭" + ChatColor.GRAY + " 1,000 · "
                        + ChatColor.YELLOW + "Shift+좌클릭" + ChatColor.GRAY + " 전액",
                ChatColor.YELLOW + "우클릭" + ChatColor.GRAY + " 10,000"));
        button(inv, holder, 16, Action.RATES, Material.CLOCK, "&f금리표", rateLore(bank, config));
        button(inv, holder, 13, Action.HEALTH, Material.IRON_BARS, "&f은행 건전성", healthLore(bank, config));

        drawDeposits(inv, holder, account);
        drawLoans(inv, holder, account);

        button(inv, holder, 45, Action.BACK, Material.ARROW, "&c뒤로",
                List.of(ChatColor.GRAY + "/menu 로 돌아갑니다"));
        button(inv, holder, 47, Action.MARKET, Material.EMERALD_BLOCK, "&a시장",
                List.of(ChatColor.GRAY + "물건을 사고팝니다", ChatColor.YELLOW + "클릭하여 열기"));
        button(inv, holder, 51, Action.DASHBOARD, Material.PAPER, "&e경제 지표",
                List.of(ChatColor.GRAY + "물가 · 금리 · 통화량 · 경기", ChatColor.YELLOW + "클릭하여 열기"));
        button(inv, holder, 53, Action.CLOSE, Material.RED_STAINED_GLASS_PANE, "&c닫기", List.of());

        player.openInventory(inv);
    }

    private void drawDeposits(Inventory inv, Holder holder, BankAccount account) {
        int day = plugin.macro().day();
        int slot = 19;
        if (account.deposits().isEmpty()) {
            inv.setItem(slot, button(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "&7정기예금 없음",
                    List.of(ChatColor.DARK_GRAY + "위의 정기예금 가입으로 만들 수 있습니다")));
            return;
        }
        for (int i = 0; i < account.deposits().size() && slot <= 25; i++, slot++) {
            TimeDeposit deposit = account.deposits().get(i);
            boolean matured = deposit.matured(day);
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "원금 " + ChatColor.WHITE + deposit.principal()
                    + ChatColor.GRAY + " · 연 " + ChatColor.WHITE
                    + BankService.percent(deposit.annualRate()));
            lore.add(ChatColor.GRAY + "쌓인 이자 " + ChatColor.YELLOW
                    + String.format(Locale.ROOT, "%.0f", deposit.accrued()));
            lore.add(matured
                    ? ChatColor.GREEN + "만기되었습니다"
                    : ChatColor.GRAY + "만기까지 " + ChatColor.WHITE + deposit.daysLeft(day) + "일");
            lore.add("");
            lore.add(ChatColor.YELLOW + "클릭하여 해지" + (matured ? "" : ChatColor.RED + " (중도 해지)"));
            inv.setItem(slot, button(matured ? Material.ENDER_CHEST : Material.BARREL,
                    "&b정기예금 " + (i + 1), lore));
            holder.deposits.put(slot, i);
        }
    }

    private void drawLoans(Inventory inv, Holder holder, BankAccount account) {
        int day = plugin.macro().day();
        int slot = 28;
        if (account.loans().isEmpty()) {
            inv.setItem(slot, button(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "&7대출 없음",
                    List.of(ChatColor.DARK_GRAY + "빚이 없습니다. 신용점수가 매일 회복됩니다.")));
            return;
        }
        for (int i = 0; i < account.loans().size() && slot <= 34; i++, slot++) {
            Loan loan = account.loans().get(i);
            boolean overdue = loan.overdue(day);
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "빌린 돈 " + ChatColor.WHITE + loan.principal()
                    + ChatColor.GRAY + " · 연 " + ChatColor.WHITE
                    + BankService.percent(loan.annualRate()));
            lore.add(ChatColor.GRAY + "남은 채무 " + ChatColor.RED + loan.owed());
            lore.add(overdue
                    ? ChatColor.RED + "연체 " + loan.overdueDays() + "일차 (가산금리 적용 중)"
                    : ChatColor.GRAY + "만기까지 " + ChatColor.WHITE + loan.daysLeft(day) + "일");
            lore.add("");
            lore.add(ChatColor.YELLOW + "클릭하여 이 대출을 전액 상환");
            inv.setItem(slot, button(overdue ? Material.REDSTONE_BLOCK : Material.PAPER,
                    (overdue ? "&c" : "&6") + "대출 " + (i + 1), lore));
            holder.loans.put(slot, i);
        }
    }

    private ItemStack accountCard(Player player, BankAccount account) {
        BankService bank = plugin.bank();
        EconomyConfig.Grade grade = bank.grade(account);
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "지갑 " + ChatColor.YELLOW + plugin.economy().balance(player)
                + plugin.rpgConfig().goldSymbol());
        lore.add(ChatColor.GRAY + "입출금 " + ChatColor.YELLOW + account.checking()
                + ChatColor.GRAY + " · 정기 " + ChatColor.YELLOW
                + (account.totalDeposits() - account.checking()));
        lore.add(ChatColor.GRAY + "채무 " + (account.totalDebt() > 0 ? ChatColor.RED : ChatColor.GRAY)
                + account.totalDebt());
        lore.add("");
        lore.add(ChatColor.GRAY + "신용점수 " + ChatColor.WHITE + account.creditScore()
                + ChatColor.GRAY + " / 1000 · 등급 " + gradeColour(grade) + grade.name());
        lore.add(ChatColor.DARK_GRAY + creditBar(account.creditScore()));
        lore.add(ChatColor.GRAY + "대출 " + account.loansTaken() + "건 · 완납 "
                + account.loansRepaid() + "건 · 연체이력 " + account.defaults() + "건");
        lore.add(ChatColor.GRAY + "받은 이자 " + account.interestEarned()
                + " · 낸 이자 " + account.interestPaid());
        return button(Material.PLAYER_HEAD, "&6" + player.getName() + " 님의 계좌", lore);
    }

    private List<String> rateLore(BankService bank, EconomyConfig config) {
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "정책금리 " + ChatColor.WHITE
                + BankService.percent(bank.policyRate()) + ChatColor.DARK_GRAY + " (중앙은행)");
        lore.add(ChatColor.GRAY + "예금 " + ChatColor.WHITE
                + BankService.percent(bank.depositRate()) + ChatColor.GRAY + " · 정기 7일 "
                + ChatColor.WHITE + BankService.percent(bank.termRate(7))
                + ChatColor.GRAY + " · 30일 " + ChatColor.WHITE
                + BankService.percent(bank.termRate(30)));
        lore.add("");
        lore.add(ChatColor.GRAY + "등급별 대출 금리:");
        for (EconomyConfig.Grade grade : config.grades()) {
            if (grade.limitMultiplier() <= 0) {
                lore.add(ChatColor.DARK_GRAY + "  " + grade.name() + " - 대출 불가");
                continue;
            }
            lore.add(ChatColor.DARK_GRAY + "  " + grade.name() + " (" + grade.minScore() + "+) "
                    + ChatColor.GRAY + BankService.percent(bank.policyRate()
                    + config.loanMarginPercent() + grade.riskPremium())
                    + " · 한도 x" + trim(grade.limitMultiplier()));
        }
        lore.add("");
        lore.add(ChatColor.DARK_GRAY + "이자는 경제일(" + config.dayMinutes()
                + "분)마다 붙습니다. 1년 = " + config.daysPerYear() + "일");
        return lore;
    }

    private List<String> healthLore(BankService bank, EconomyConfig config) {
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "예금 총액 " + ChatColor.WHITE
                + String.format(Locale.ROOT, "%,d", bank.totalDeposits()));
        lore.add(ChatColor.GRAY + "대출 총액 " + ChatColor.WHITE
                + String.format(Locale.ROOT, "%,d", bank.totalLoans()));
        lore.add(ChatColor.GRAY + "금고 현금 " + ChatColor.WHITE
                + String.format(Locale.ROOT, "%,d", bank.cash()));
        lore.add(ChatColor.GRAY + "지급준비금 " + ChatColor.WHITE
                + String.format(Locale.ROOT, "%,d", bank.requiredReserves())
                + ChatColor.DARK_GRAY + " (예금의 " + (int) config.reserveRatioPercent() + "%)");
        lore.add(ChatColor.GRAY + "대출 여력 " + ChatColor.GREEN
                + String.format(Locale.ROOT, "%,d", bank.lendingCapacity()));
        lore.add("");
        lore.add(ChatColor.GRAY + "예대율 " + ChatColor.WHITE
                + String.format(Locale.ROOT, "%.0f%%", bank.loanToDepositPercent())
                + ChatColor.GRAY + " · 연체율 " + ChatColor.WHITE
                + String.format(Locale.ROOT, "%.1f%%", bank.delinquencyPercent()));
        lore.add(ChatColor.GRAY + "자기자본 " + (bank.equity() < 0 ? ChatColor.RED : ChatColor.WHITE)
                + String.format(Locale.ROOT, "%,d", bank.equity()));
        if (bank.centralBankLoans() > 0) {
            lore.add(ChatColor.RED + "중앙은행 차입 "
                    + String.format(Locale.ROOT, "%,d", bank.centralBankLoans()));
        }
        lore.add("");
        lore.add(ChatColor.DARK_GRAY + "통화승수 한계 = 1 / 지급준비율 = "
                + String.format(Locale.ROOT, "%.1f", 100.0 / config.reserveRatioPercent()) + "배");
        return lore;
    }

    /** Ten blocks of credit score, so the grade is readable at a glance. */
    private static String creditBar(int score) {
        int filled = Math.clamp(score / 100, 0, 10);
        return "■".repeat(filled) + "□".repeat(10 - filled);
    }

    private static ChatColor gradeColour(EconomyConfig.Grade grade) {
        if (grade.limitMultiplier() <= 0) {
            return ChatColor.DARK_RED;
        }
        if (grade.riskPremium() <= 1.0) {
            return ChatColor.GREEN;
        }
        if (grade.riskPremium() <= 4.5) {
            return ChatColor.YELLOW;
        }
        return ChatColor.RED;
    }

    private static String trim(double value) {
        return MarketMenu.trim(value);
    }

    private static String trimPercent(double value) {
        return MarketMenu.trim(value) + "%";
    }

    private void button(Inventory inv, Holder holder, int slot, Action action,
                        Material material, String name, List<String> lore) {
        inv.setItem(slot, button(material, name, lore));
        holder.actions.put(slot, action);
    }

    private ItemStack filler() {
        return button(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
    }

    private ItemStack button(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
        if (!lore.isEmpty()) {
            meta.setLore(lore);
        }
        stack.setItemMeta(meta);
        return stack;
    }
}
