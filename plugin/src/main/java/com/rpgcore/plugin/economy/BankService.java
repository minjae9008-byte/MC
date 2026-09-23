package com.rpgcore.plugin.economy;

import com.rpgcore.plugin.RpgCorePlugin;
import com.rpgcore.plugin.util.DeferredSave;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The bank: where gold that is not being spent goes, and where gold that does
 * not exist yet comes from.
 *
 * The balance sheet is the whole design, and it is kept honest rather than
 * simulated. Two numbers are stored - the cash in the vault and what the
 * central bank has lent the bank - and everything else is derived from the
 * accounts themselves:
 *
 *   자산 = 현금 + 대출채권
 *   부채 = 예금 + 중앙은행 차입
 *   자본 = 자산 - 부채
 *
 * Every operation moves two of those together, so equity only changes when
 * the bank actually earns or loses: interest accrued on a loan is income,
 * interest credited to a depositor is expense, and a written-off loan is a
 * loss. If equity goes negative the central bank recapitalises it by printing,
 * which is visible on the dashboard as new money - a bailout that costs the
 * whole server a little inflation rather than nothing at all.
 *
 * Lending is limited by the reserve requirement, not by fiat: the bank may
 * lend out everything except {@code reserve-ratio-percent} of its deposits.
 * That is what creates money here - a loan pays out cash that the borrower
 * can deposit again, and the deposit can be lent again, up to the multiplier
 * 1 / reserve ratio. The dashboard's 통화승수 line is that ratio, measured.
 */
public final class BankService {

    private final RpgCorePlugin plugin;
    private final EconomyConfig config;
    private final BankStorage storage;
    private final DeferredSave writer;

    private final Map<UUID, BankAccount> accounts = new HashMap<>();

    /** Gold actually in the vault. */
    private long cash;
    /** Emergency liquidity the central bank has advanced. Printed money. */
    private long centralBankLoans;
    /** Cumulative bailouts, for the dashboard. */
    private long recapitalised;
    /** Loans written off, cumulative. */
    private long writtenOff;

    public BankService(RpgCorePlugin plugin, EconomyConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.storage = new BankStorage(plugin);
        this.writer = new DeferredSave(plugin, BankStorage.FILE, () -> storage.build(this));
    }

    public boolean enabled() {
        return plugin.rpgConfig().bankEnabled();
    }

    public void load() {
        accounts.clear();
        cash = 0;
        centralBankLoans = 0;
        storage.restore(this);
        plugin.getLogger().info("Bank loaded: " + accounts.size() + " accounts, vault "
                + cash + " gold, loans out " + totalLoans() + ".");
    }

    public void save() {
        writer.markDirty();
    }

    public void saveNow() {
        writer.flushNow();
    }

    public Runnable pendingWrite() {
        return writer.pendingWrite();
    }

    /**
     * Forces the ledger to disk now.
     *
     * Used by every operation that moves gold between a player's balance and
     * the vault. The player's side of that move lives on the vanilla
     * scoreboard, which is written when the world saves rather than when we
     * ask, so the two halves cannot be made atomic from here - but writing
     * this side immediately keeps the window to milliseconds instead of
     * minutes. The auction house takes bids under exactly the same terms.
     */
    private void commit() {
        writer.flushBlocking();
    }

    // -------------------------------------------------------------- accounts

    public BankAccount account(Player player) {
        BankAccount account = accounts.computeIfAbsent(player.getUniqueId(),
                id -> new BankAccount(id, player.getName(), config.creditStart()));
        account.name(player.getName());
        account.level(plugin.players().get(player).level());
        return account;
    }

    public BankAccount peek(UUID owner) {
        return accounts.get(owner);
    }

    /**
     * The bank account of something that is not a person - a company.
     *
     * Same ledger, same loan machinery, same arrears handling. Only the
     * credit limit differs: a company borrows against what it owns rather
     * than against its level and its savings, because a company does not
     * have a level and its savings are its working capital.
     */
    public BankAccount accountFor(UUID owner, String name, long assets) {
        BankAccount account = accounts.computeIfAbsent(owner,
                id -> new BankAccount(id, name, config.creditStart()));
        account.name(name);
        account.corporate(true);
        account.declaredAssets(assets);
        return account;
    }

    public Map<UUID, BankAccount> accounts() {
        return accounts;
    }

    public int accountCount() {
        return accounts.size();
    }

    // ----------------------------------------------------------- the ledger

    public long cash() {
        return cash;
    }

    public long centralBankLoans() {
        return centralBankLoans;
    }

    public long recapitalised() {
        return recapitalised;
    }

    public long writtenOff() {
        return writtenOff;
    }

    public long totalDeposits() {
        long total = 0;
        for (BankAccount account : accounts.values()) {
            total += account.totalDeposits();
        }
        return total;
    }

    /** Demand deposits only - the part that counts towards M1. */
    public long demandDeposits() {
        long total = 0;
        for (BankAccount account : accounts.values()) {
            total += account.checking();
        }
        return total;
    }

    public long termDeposits() {
        return totalDeposits() - demandDeposits();
    }

    public long totalLoans() {
        long total = 0;
        for (BankAccount account : accounts.values()) {
            total += account.totalDebt();
        }
        return total;
    }

    /** Assets minus liabilities. Negative means the bank needs a bailout. */
    public long equity() {
        return cash + totalLoans() - totalDeposits() - centralBankLoans;
    }

    public long requiredReserves() {
        return Math.round(totalDeposits() * config.reserveRatio());
    }

    /** Cash the bank may lend out of its own funds, after reserves. */
    public long ownFunds() {
        return Math.max(0, cash - requiredReserves());
    }

    /** What the central bank will still advance against the facility. */
    public long facilityHeadroom() {
        return Math.max(0, config.centralBankFacility() - centralBankLoans);
    }

    /**
     * What the bank can lend right now.
     *
     * Own funds first, then the central bank's standing facility. Without the
     * facility a server on its first day has no deposits, therefore no
     * lendable cash, therefore no loans at all - the feature would simply be
     * dead until somebody happened to save. With it, credit exists from the
     * start and its cost is visible: facility money is printed money, so it
     * lands in the money supply and works through to prices.
     */
    public long lendingCapacity() {
        return ownFunds() + facilityHeadroom();
    }

    /** Outstanding loans over deposits - how hard the bank is working. */
    public double loanToDepositPercent() {
        long deposits = totalDeposits();
        return deposits <= 0 ? 0 : totalLoans() / (double) deposits * 100.0;
    }

    /** Share of lending that is behind on payments. */
    public double delinquencyPercent() {
        int today = today();
        long overdue = 0;
        long total = 0;
        for (BankAccount account : accounts.values()) {
            for (Loan loan : account.loans()) {
                total += loan.owed();
                if (loan.overdue(today)) {
                    overdue += loan.owed();
                }
            }
        }
        return total <= 0 ? 0 : overdue / (double) total * 100.0;
    }

    // ----------------------------------------------------------------- rates

    public double policyRate() {
        return plugin.macro() == null ? config.neutralRatePercent() : plugin.macro().policyRate();
    }

    /** What a demand account earns, as an annual percentage. */
    public double depositRate() {
        return Math.max(0, policyRate() - config.depositMarginPercent());
    }

    /**
     * A term deposit pays the demand rate plus a premium for the lock-up -
     * but never more than the cheapest loan costs.
     *
     * The cap is not tuning, it is a safety rail. Without it a long enough
     * term at a high enough premium pays more than the best borrower is
     * charged, and then the winning move is to borrow the maximum and deposit
     * it: free gold, funded by the bank, compounding every day. The ceiling
     * makes that trade lose money at every rate the config can produce.
     */
    public double termRate(int days) {
        double best = policyRate() + config.loanMarginPercent() - 0.5;
        return Math.max(0, Math.min(depositRate() + config.termPremiumPerDayPercent() * days, best));
    }

    /** What this borrower is charged: policy, plus the spread, plus their risk. */
    public double loanRate(BankAccount account) {
        return policyRate() + config.loanMarginPercent() + grade(account).riskPremium();
    }

    /**
     * Sets a company's rating.
     *
     * Only a company's: a player's score is earned through this class's own
     * repayment and arrears handling, and letting anything outside set it
     * would make that record meaningless.
     */
    public void rateCorporate(BankAccount account, int score) {
        if (account != null && account.corporate()) {
            account.creditScore(score);
        }
    }

    public EconomyConfig.Grade grade(BankAccount account) {
        return config.gradeFor(effectiveScore(account));
    }

    /**
     * The score a lender actually goes on: what the account earned, plus
     * whatever their trade is worth to a bank.
     *
     * Only for people, and only while they are online - an offline player is
     * not applying for anything, and a company's rating comes from its books.
     */
    public int effectiveScore(BankAccount account) {
        if (account.corporate() || plugin.jobs() == null) {
            return account.creditScore();
        }
        Player online = plugin.getServer().getPlayer(account.owner());
        if (online == null) {
            return account.creditScore();
        }
        return Math.clamp(account.creditScore()
                + plugin.jobs().economyOf(online).creditBonus(), 0, 1000);
    }

    /**
     * How much this player could owe in total.
     *
     * Deliberately built from things the player controls - their level, what
     * they have saved, and their record - rather than from their current
     * wallet. A limit that tracked the wallet would lend most to whoever needs
     * it least and would swing wildly as they spend.
     */
    public long creditLimit(BankAccount account) {
        return Math.max(0, Math.round(capacity(account) * grade(account).limitMultiplier()));
    }

    /** What the borrower can service, before their grade is applied. */
    private double capacity(BankAccount account) {
        if (account.corporate()) {
            return account.declaredAssets() * config.corporateLimitPercent() / 100.0;
        }
        return config.limitBase()
                + (long) config.limitPerLevel() * account.level()
                + account.totalDeposits() * config.limitDepositPercent() / 100.0;
    }

    /** The debt-to-income ceiling, which bites before the credit limit does. */
    public long dtiCeiling(BankAccount account) {
        return Math.round(capacity(account) * config.dtiPercent() / 100.0);
    }

    public long borrowable(BankAccount account) {
        long headroom = Math.min(creditLimit(account), dtiCeiling(account)) - account.totalDebt();
        return Math.max(0, Math.min(headroom, lendingCapacity()));
    }

    // ------------------------------------------------------------- deposits

    public boolean deposit(Player player, long amount) {
        if (!guard(player)) {
            return false;
        }
        if (amount <= 0) {
            player.sendMessage(ChatColor.RED + "[은행] 1 이상을 넣어야 합니다.");
            return false;
        }
        int take = (int) Math.min(Integer.MAX_VALUE, amount);
        if (!plugin.economy().take(player, take)) {
            player.sendMessage(ChatColor.RED + "[은행] 골드가 부족합니다. (보유 "
                    + plugin.economy().balance(player) + ")");
            return false;
        }
        BankAccount account = account(player);
        account.addChecking(take);
        cash += take;
        commit();
        player.sendMessage(ChatColor.GREEN + "[은행] " + take + plugin.rpgConfig().goldSymbol()
                + " 를 입금했습니다. " + ChatColor.GRAY + "(예금 " + account.checking()
                + ", 지갑 " + plugin.economy().balance(player) + ")");
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.7F, 1.4F);
        return true;
    }

    public boolean withdraw(Player player, long amount) {
        if (!guard(player)) {
            return false;
        }
        BankAccount account = account(player);
        if (amount <= 0 || amount > account.checking()) {
            player.sendMessage(ChatColor.RED + "[은행] 출금할 수 있는 금액이 아닙니다. (예금 "
                    + account.checking() + ")");
            return false;
        }
        // A depositor is always paid. If the vault is short because the money
        // is out on loan, the central bank lends the difference - which is a
        // bank run answered by printing, and the dashboard shows it as such.
        if (cash < amount) {
            long need = amount - cash;
            centralBankLoans += need;
            cash += need;
            if (plugin.macro() != null) {
                plugin.macro().recordPrinting(need);
            }
            plugin.getLogger().warning("Bank vault short by " + need
                    + " gold on a withdrawal; central bank liquidity advanced.");
        }
        account.addChecking(-amount);
        cash -= amount;
        commit();
        plugin.economy().refund(player, (int) Math.min(Integer.MAX_VALUE, amount));
        player.sendMessage(ChatColor.GREEN + "[은행] " + amount + plugin.rpgConfig().goldSymbol()
                + " 를 찾았습니다. " + ChatColor.GRAY + "(예금 " + account.checking()
                + ", 지갑 " + plugin.economy().balance(player) + ")");
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.7F, 1.0F);
        return true;
    }

    public boolean openTerm(Player player, long amount, int days) {
        if (!guard(player)) {
            return false;
        }
        int term = Math.clamp(days, config.termMinDays(), config.termMaxDays());
        BankAccount account = account(player);
        if (amount <= 0 || amount > account.checking()) {
            player.sendMessage(ChatColor.RED + "[은행] 입출금 계좌의 잔액이 모자랍니다. (잔액 "
                    + account.checking() + ") 먼저 /bank deposit 하세요.");
            return false;
        }
        double rate = termRate(term);
        account.addChecking(-amount);
        account.deposits().add(TimeDeposit.open(amount, rate, today(), term));
        commit();
        player.sendMessage(ChatColor.GREEN + "[은행] 정기예금 " + amount
                + plugin.rpgConfig().goldSymbol() + " · " + term + "일 · 연 "
                + percent(rate) + " 로 맡겼습니다.");
        player.sendMessage(ChatColor.GRAY + "  만기 예상 수령액 약 "
                + (amount + Math.round(amount * config.dailyFrom(rate) * term)) + ". 중도 해지하면 이자의 "
                + (int) config.earlyWithdrawalPenaltyPercent() + "% 를 못 받습니다.");
        return true;
    }

    /** Closes a term deposit by its position in the player's list. */
    public boolean closeTerm(Player player, int index) {
        if (!guard(player)) {
            return false;
        }
        BankAccount account = account(player);
        if (index < 0 || index >= account.deposits().size()) {
            player.sendMessage(ChatColor.RED + "[은행] 그런 정기예금이 없습니다.");
            return false;
        }
        TimeDeposit deposit = account.deposits().remove(index);
        int today = today();
        long interest;
        if (deposit.matured(today)) {
            interest = (long) Math.floor(deposit.accrued());
        } else {
            interest = (long) Math.floor(deposit.accrued()
                    * (1.0 - config.earlyWithdrawalPenaltyPercent() / 100.0));
            player.sendMessage(ChatColor.YELLOW + "[은행] 중도 해지입니다. 이자의 "
                    + (int) config.earlyWithdrawalPenaltyPercent() + "% 를 뗍니다.");
        }
        account.addChecking(deposit.principal() + interest);
        account.addInterestEarned(interest);
        commit();
        player.sendMessage(ChatColor.GREEN + "[은행] 정기예금을 해지했습니다. 원금 "
                + deposit.principal() + " + 이자 " + interest + " = "
                + (deposit.principal() + interest) + plugin.rpgConfig().goldSymbol()
                + " 가 입출금 계좌로 들어왔습니다.");
        return true;
    }

    // ---------------------------------------------------------------- bonds

    /** What the state pays to borrow: the policy rate plus a small premium. */
    public double bondRate() {
        // Capped under the cheapest loan for the same reason term deposits
        // are: otherwise borrowing to buy bonds is free money.
        double best = policyRate() + config.loanMarginPercent() - 0.5;
        return Math.max(0, Math.min(policyRate() + config.bondPremiumPercent(), best));
    }

    public long bondsOutstanding() {
        long total = 0;
        for (BankAccount account : accounts.values()) {
            total += account.bondHoldings();
        }
        return total;
    }

    /**
     * Lends gold to the state.
     *
     * The gold leaves circulation the moment it is bought - it goes into the
     * treasury, which is not counted as money - so a bond issue is a
     * contraction, and redeeming one is an expansion. That is the whole point
     * of the instrument: it lets the state raise money now without printing,
     * and pay for it later.
     */
    public boolean buyBond(Player player, long amount, int days) {
        if (!guard(player)) {
            return false;
        }
        int term = Math.clamp(days, 1, config.bondMaxDays());
        if (amount < config.bondMin()) {
            player.sendMessage(ChatColor.RED + "[국채] 최소 매입 금액은 " + config.bondMin()
                    + plugin.rpgConfig().goldSymbol() + " 입니다.");
            return false;
        }
        if (bondsOutstanding() + amount > config.bondTotalLimit()) {
            player.sendMessage(ChatColor.RED + "[국채] 발행 한도가 찼습니다. (한도 "
                    + config.bondTotalLimit() + ", 발행 잔액 " + bondsOutstanding() + ")");
            return false;
        }
        int take = (int) Math.min(Integer.MAX_VALUE, amount);
        if (!plugin.economy().take(player, take)) {
            player.sendMessage(ChatColor.RED + "[국채] 골드가 부족합니다. (보유 "
                    + plugin.economy().balance(player) + ")");
            return false;
        }
        BankAccount account = account(player);
        double rate = bondRate();
        account.bonds().add(TimeDeposit.open(take, rate, today(), term));
        plugin.market().creditTreasury(take);
        commit();
        player.sendMessage(ChatColor.GREEN + "[국채] " + take + plugin.rpgConfig().goldSymbol()
                + " · " + term + "일 · 연 " + percent(rate) + " 로 매입했습니다.");
        player.sendMessage(ChatColor.GRAY + "  만기 " + (today() + term) + "일차에 원금과 이자를 "
                + "국고가 돌려줍니다. 중도 환매는 없습니다.");
        return true;
    }

    /** Matured bonds, paid out of the treasury. */
    private void redeemBonds(BankAccount account, int day) {
        List<TimeDeposit> matured = new ArrayList<>();
        for (TimeDeposit bond : account.bonds()) {
            if (bond.matured(day)) {
                matured.add(bond);
                continue;
            }
            bond.accrue(config.dailyFrom(bond.annualRate()));
        }
        for (TimeDeposit bond : matured) {
            account.bonds().remove(bond);
            long interest = (long) Math.floor(bond.accrued());
            long total = bond.principal() + interest;
            plugin.market().debitTreasury(total);
            account.addInterestEarned(interest);
            plugin.mailbox().giveGold(account.owner(),
                    (int) Math.min(Integer.MAX_VALUE, total), "국채 상환");
            tell(account, ChatColor.GREEN + "[국채] 만기 상환 " + total
                    + plugin.rpgConfig().goldSymbol() + " (원금 " + bond.principal()
                    + " + 이자 " + interest + ")");
        }
    }

    // ---------------------------------------------------------------- loans

    public boolean borrow(Player player, long amount, int days) {
        if (!guard(player)) {
            return false;
        }
        BankAccount account = account(player);
        int term = Math.clamp(days, 1, config.loanMaxDays());
        if (amount < config.loanMin()) {
            player.sendMessage(ChatColor.RED + "[은행] 최소 대출 금액은 "
                    + config.loanMin() + plugin.rpgConfig().goldSymbol() + " 입니다.");
            return false;
        }
        if (account.loans().size() >= config.maxLoans()) {
            player.sendMessage(ChatColor.RED + "[은행] 대출은 동시에 "
                    + config.maxLoans() + "건까지입니다. 먼저 갚으세요.");
            return false;
        }
        if (account.hasOverdue(today())) {
            player.sendMessage(ChatColor.RED + "[은행] 연체 중에는 새로 빌릴 수 없습니다.");
            return false;
        }
        EconomyConfig.Grade grade = grade(account);
        if (grade.limitMultiplier() <= 0) {
            player.sendMessage(ChatColor.RED + "[은행] 신용등급 " + grade.name()
                    + " 은(는) 대출 대상이 아닙니다. 신용점수 " + account.creditScore()
                    + ". 빚을 갚고 기다리면 점수가 회복됩니다.");
            return false;
        }
        long limit = Math.min(creditLimit(account), dtiCeiling(account));
        if (account.totalDebt() + amount > limit) {
            player.sendMessage(ChatColor.RED + "[은행] 한도를 넘습니다. 한도 " + limit
                    + ", 기존 채무 " + account.totalDebt() + ", 남은 여유 "
                    + Math.max(0, limit - account.totalDebt()) + ".");
            return false;
        }
        // The reserve requirement, enforced where it actually bites. This is
        // the credit crunch: plenty of willing borrowers, no lendable cash.
        if (amount > lendingCapacity()) {
            player.sendMessage(ChatColor.RED + "[은행] 은행 대출 여력이 부족합니다. 지금 빌려줄 수 있는 돈은 "
                    + lendingCapacity() + plugin.rpgConfig().goldSymbol()
                    + " 입니다. " + ChatColor.GRAY + "(예금이 늘면 여력도 늘어납니다)");
            return false;
        }

        long payout = openLoan(account, amount, term);
        plugin.economy().refund(player, (int) Math.min(Integer.MAX_VALUE, payout));
        double rate = account.loans().get(account.loans().size() - 1).annualRate();
        long fee = amount - payout;

        player.sendMessage(ChatColor.GREEN + "[은행] " + amount + plugin.rpgConfig().goldSymbol()
                + " 를 " + term + "일 · 연 " + percent(rate) + " 로 빌렸습니다. "
                + ChatColor.GRAY + "(취급 수수료 " + fee + " 을 떼고 " + payout + " 지급)");
        player.sendMessage(ChatColor.YELLOW + "  만기 " + (today() + term) + "일차, 그때까지 갚을 돈 약 "
                + Math.round(amount * Math.pow(1 + config.dailyFrom(rate), term))
                + ". 연체하면 가산금리 " + (int) config.overdueExtraPercent()
                + "% 가 붙고 신용점수가 깎입니다.");
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8F, 0.8F);
        return true;
    }

    /**
     * Borrows from the central bank whatever this loan needs and the vault
     * does not have. This is where money is created: the gold did not exist
     * a moment ago and now it is in a borrower's hands.
     */
    private void drawOnFacility(long amount) {
        long shortfall = amount - ownFunds();
        if (shortfall <= 0) {
            return;
        }
        long draw = Math.min(shortfall, facilityHeadroom());
        if (draw <= 0) {
            return;
        }
        centralBankLoans += draw;
        cash += draw;
        if (plugin.macro() != null) {
            plugin.macro().recordPrinting(draw);
        }
    }

    /**
     * Interest on the facility, and repayment of it out of anything spare.
     *
     * Facility money is not free: it costs the policy rate, which is the
     * lever the central bank actually has over lending. When rates rise,
     * lending funded this way stops paying for itself and the bank's own
     * deposits become the cheaper source - which is the transmission
     * mechanism working as it should.
     */
    private void settleFacility() {
        if (centralBankLoans <= 0) {
            return;
        }
        centralBankLoans += Math.round(centralBankLoans * config.dailyFrom(policyRate()));
        long repay = Math.min(ownFunds(), centralBankLoans);
        if (repay > 0) {
            cash -= repay;
            centralBankLoans -= repay;
        }
    }

    /**
     * Why this account cannot borrow this much, or null when it can.
     *
     * Shared by people and companies, because the reasons are the same ones:
     * too small, too many open, already behind, no credit, over the limit, or
     * the bank has nothing lendable left.
     */
    public String refuseLoan(BankAccount account, long amount, int days) {
        if (!enabled()) {
            return "이 서버에서는 은행을 쓸 수 없습니다.";
        }
        if (amount < config.loanMin()) {
            return "최소 대출 금액은 " + config.loanMin() + " 골드입니다.";
        }
        if (account.loans().size() >= config.maxLoans()) {
            return "대출은 동시에 " + config.maxLoans() + "건까지입니다.";
        }
        if (account.hasOverdue(today())) {
            return "연체 중에는 새로 빌릴 수 없습니다.";
        }
        EconomyConfig.Grade grade = grade(account);
        if (grade.limitMultiplier() <= 0) {
            return "신용등급 " + grade.name() + " 은(는) 대출 대상이 아닙니다. (점수 "
                    + account.creditScore() + ")";
        }
        long limit = Math.min(creditLimit(account), dtiCeiling(account));
        if (account.totalDebt() + amount > limit) {
            return "한도를 넘습니다. 한도 " + limit + ", 기존 채무 " + account.totalDebt()
                    + ", 남은 여유 " + Math.max(0, limit - account.totalDebt()) + ".";
        }
        if (amount > lendingCapacity()) {
            return "은행 대출 여력이 부족합니다. 지금 빌려줄 수 있는 돈은 "
                    + lendingCapacity() + " 골드입니다.";
        }
        return null;
    }

    /**
     * Opens the loan and returns the net payout. The caller moves the gold -
     * into a wallet for a person, into the till for a company.
     *
     * Call {@link #refuseLoan} first; this does not check again.
     */
    public long openLoan(BankAccount account, long amount, int days) {
        int term = Math.clamp(days, 1, config.loanMaxDays());
        double rate = loanRate(account);
        long fee = Math.round(amount * config.originationFeePercent() / 100.0);
        drawOnFacility(amount);
        account.loans().add(Loan.open(amount, rate, today(), term));
        account.countLoanTaken();
        cash -= amount;
        commit();
        // The fee leaves circulation entirely rather than becoming bank
        // profit: it is a tax, and it is the one part of borrowing that is
        // not simply moved around.
        if (fee > 0) {
            plugin.market().creditTreasury(fee);
            if (plugin.macro() != null) {
                plugin.macro().collectFee(fee);
            }
        }
        return amount - fee;
    }

    /**
     * Takes a repayment from something that is not a player - a company
     * paying down its own loans out of its till.
     *
     * @return how much was actually applied
     */
    public long repayFor(BankAccount account, long amount) {
        long applied = applyRepayment(account, amount);
        cash += applied;
        if (applied > 0) {
            commit();
        }
        return applied;
    }

    /**
     * Writes off what a failed borrower cannot pay.
     *
     * The bank loses it - that is what a bad debt is - and the loss lands in
     * its own capital, where a run of them will eventually need the central
     * bank. Nothing is quietly forgiven.
     */
    public void writeOff(BankAccount account) {
        long owed = account.totalDebt();
        if (owed <= 0) {
            return;
        }
        writtenOff += owed;
        account.loans().clear();
        account.countDefault();
        account.bumpCredit(config.creditOnDefault());
        commit();
    }

    /** Pays down the oldest loan first, then the next. Returns what was used. */
    public long repay(Player player, long amount) {
        if (!guard(player)) {
            return 0;
        }
        BankAccount account = account(player);
        if (account.loans().isEmpty()) {
            player.sendMessage(ChatColor.GRAY + "[은행] 갚을 빚이 없습니다.");
            return 0;
        }
        long owed = account.totalDebt();
        long want = Math.min(amount, owed);
        if (want <= 0) {
            player.sendMessage(ChatColor.RED + "[은행] 1 이상을 갚아야 합니다.");
            return 0;
        }
        int take = (int) Math.min(Integer.MAX_VALUE, want);
        if (!plugin.economy().take(player, take)) {
            player.sendMessage(ChatColor.RED + "[은행] 골드가 부족합니다. (보유 "
                    + plugin.economy().balance(player) + ", 총 채무 " + owed + ")");
            return 0;
        }
        long applied = applyRepayment(account, take);
        cash += applied;
        // Anything the loans did not need goes back rather than vanishing.
        if (applied < take) {
            plugin.economy().refund(player, (int) (take - applied));
        }
        commit();
        player.sendMessage(ChatColor.GREEN + "[은행] " + applied + plugin.rpgConfig().goldSymbol()
                + " 를 갚았습니다. " + ChatColor.GRAY + "(남은 채무 " + account.totalDebt()
                + ", 신용점수 " + account.creditScore() + ")");
        return applied;
    }

    /**
     * Clears one specific loan, the one the player clicked.
     *
     * Separate from {@link #repay(Player, long)} because that one pays the
     * oldest first: paying "this loan" from a screen that shows three of them
     * has to pay the one under the cursor, or the screen is lying.
     */
    public boolean repayLoan(Player player, int index) {
        if (!guard(player)) {
            return false;
        }
        BankAccount account = account(player);
        if (index < 0 || index >= account.loans().size()) {
            player.sendMessage(ChatColor.RED + "[은행] 그런 대출이 없습니다.");
            return false;
        }
        Loan loan = account.loans().get(index);
        long owed = loan.owed();
        int take = (int) Math.min(Integer.MAX_VALUE, owed);
        if (!plugin.economy().take(player, take)) {
            player.sendMessage(ChatColor.RED + "[은행] 골드가 부족합니다. 이 대출 잔액 " + owed
                    + ", 보유 " + plugin.economy().balance(player)
                    + ". " + ChatColor.GRAY + "(/bank repay <금액> 으로 나눠 갚을 수 있습니다)");
            return false;
        }
        long paid = loan.pay(take);
        cash += paid;
        if (paid < take) {
            plugin.economy().refund(player, (int) (take - paid));
        }
        if (loan.settled()) {
            account.loans().remove(index);
            account.countLoanRepaid();
            account.bumpCredit(loan.overdue(today())
                    ? config.creditOnRepay() / 3 : config.creditOnRepay());
        }
        commit();
        player.sendMessage(ChatColor.GREEN + "[은행] 대출 " + (index + 1) + "번을 " + paid
                + plugin.rpgConfig().goldSymbol() + " 로 갚았습니다. " + ChatColor.GRAY
                + "(남은 채무 " + account.totalDebt() + ", 신용점수 " + account.creditScore() + ")");
        return true;
    }

    /**
     * Applies a payment across the account's loans, oldest first, and awards
     * credit for every loan it clears.
     */
    private long applyRepayment(BankAccount account, long amount) {
        long left = amount;
        Iterator<Loan> it = account.loans().iterator();
        long used = 0;
        List<Loan> cleared = new ArrayList<>();
        while (it.hasNext() && left > 0) {
            Loan loan = it.next();
            long paid = loan.pay(left);
            left -= paid;
            used += paid;
            if (loan.settled()) {
                cleared.add(loan);
                it.remove();
            }
        }
        for (Loan loan : cleared) {
            account.countLoanRepaid();
            // Paying late still counts, but only paying on time builds a
            // record: otherwise the cheapest strategy is always to be late.
            account.bumpCredit(loan.overdue(today())
                    ? config.creditOnRepay() / 3 : config.creditOnRepay());
        }
        return used;
    }

    /**
     * Takes the bank's cut of a payment owed to someone in arrears.
     *
     * Called by the market before it pays out a sale. Without it, a defaulter
     * simply stops opening their bank screen and keeps trading, and a loan
     * becomes a gift to anyone patient enough to ignore it.
     *
     * @return how much was seized; the caller pays out the remainder
     */
    public long garnish(Player player, long proceeds) {
        if (!enabled() || proceeds <= 0) {
            return 0;
        }
        BankAccount account = peek(player.getUniqueId());
        if (account == null || !account.hasOverdue(today())) {
            return 0;
        }
        long seize = Math.min(account.totalDebt(),
                Math.round(proceeds * config.garnishPercent() / 100.0));
        if (seize <= 0) {
            return 0;
        }
        long applied = applyRepayment(account, seize);
        cash += applied;
        save();
        if (applied > 0) {
            player.sendMessage(ChatColor.RED + "[은행] 연체 중이라 판매 대금에서 " + applied
                    + plugin.rpgConfig().goldSymbol() + " 를 상환에 충당했습니다. "
                    + ChatColor.GRAY + "(남은 채무 " + account.totalDebt() + ")");
        }
        return applied;
    }

    // ------------------------------------------------------------ daily pass

    /**
     * One economic day of banking: interest both ways, deposits maturing,
     * arrears chased, and the bank's own solvency checked.
     */
    void dailyClose(int day) {
        double depositDaily = config.dailyFrom(depositRate());
        for (BankAccount account : accounts.values()) {
            accrueDeposits(account, depositDaily, day);
            redeemBonds(account, day);
            accrueLoans(account, day);
            chaseArrears(account, day);
            recoverCredit(account);
            account.lastTouchedDay(day);
        }
        settleFacility();
        rescueIfInsolvent();
        save();
    }

    private void accrueDeposits(BankAccount account, double dailyRate, int day) {
        if (account.checking() > 0 && dailyRate > 0) {
            long interest = (long) Math.floor(account.checking() * dailyRate);
            if (interest > 0) {
                account.addChecking(interest);
                account.addInterestEarned(interest);
            }
        }
        List<TimeDeposit> matured = new ArrayList<>();
        for (TimeDeposit deposit : account.deposits()) {
            if (deposit.matured(day)) {
                matured.add(deposit);
                continue;
            }
            deposit.accrue(config.dailyFrom(deposit.annualRate()));
        }
        for (TimeDeposit deposit : matured) {
            account.deposits().remove(deposit);
            long interest = (long) Math.floor(deposit.accrued());
            account.addChecking(deposit.principal() + interest);
            account.addInterestEarned(interest);
            tell(account, ChatColor.GREEN + "[은행] 정기예금이 만기되었습니다. 원금 "
                    + deposit.principal() + " + 이자 " + interest
                    + " 가 입출금 계좌로 들어왔습니다.");
        }
    }

    private void accrueLoans(BankAccount account, int day) {
        for (Loan loan : account.loans()) {
            double rate = loan.annualRate()
                    + (loan.overdue(day) ? config.overdueExtraPercent() : 0);
            long interest = Math.round(loan.accrue(config.dailyFrom(rate)));
            if (interest > 0) {
                account.addInterestPaid(interest);
            }
        }
    }

    /**
     * Arrears, in the order a bank actually collects: the deposit it is
     * already holding first, then the credit record, then a write-off.
     */
    private void chaseArrears(BankAccount account, int day) {
        List<Loan> defaulted = new ArrayList<>();
        for (Loan loan : account.loans()) {
            if (!loan.overdue(day)) {
                continue;
            }
            loan.markOverdueDay();
            account.bumpCredit(-config.overdueCreditDropPerDay());

            // Offset: the bank takes what it is owed out of the deposit it is
            // sitting on. Assets and liabilities fall together, so this costs
            // the bank nothing and the borrower everything they had saved.
            if (account.checking() > 0) {
                long offset = Math.min(account.checking(), loan.owed());
                loan.pay(offset);
                account.addChecking(-offset);
                if (offset > 0) {
                    tell(account, ChatColor.RED + "[은행] 연체로 예금 " + offset
                            + plugin.rpgConfig().goldSymbol() + " 를 상계 처리했습니다.");
                }
            }
            if (loan.overdueDays() >= config.loanDefaultDays() && !loan.settled()) {
                defaulted.add(loan);
            } else if (loan.overdueDays() == 1) {
                tell(account, ChatColor.RED + "[은행] 대출이 연체되었습니다. 가산금리 "
                        + (int) config.overdueExtraPercent() + "% 가 붙습니다. 남은 채무 "
                        + loan.owed() + ".");
            }
        }
        for (Loan loan : defaulted) {
            account.loans().remove(loan);
            // Written off, not forgiven: equity falls by the whole balance,
            // which is what a bad debt costs a bank.
            writtenOff += loan.owed();
            account.countDefault();
            account.bumpCredit(config.creditOnDefault());
            tell(account, ChatColor.DARK_RED + "[은행] 채무불이행 처리되었습니다. 신용점수 "
                    + account.creditScore() + " (등급 " + grade(account).name()
                    + "). 당분간 대출이 막힙니다.");
        }
        if (!defaulted.isEmpty()) {
            plugin.getLogger().info("Bank wrote off " + defaulted.size()
                    + " loan(s) for " + account.name() + ".");
        }
    }

    /** A clean record heals, slowly, so a bad month is not a life sentence. */
    private void recoverCredit(BankAccount account) {
        if (account.loans().isEmpty() && config.creditRecoveryPerDay() > 0) {
            account.bumpCredit(config.creditRecoveryPerDay());
        }
    }

    /**
     * If the bank's own capital has gone negative - too many write-offs, or
     * paying depositors more than borrowers paid it - the central bank prints
     * the difference. Nobody loses their deposit; everybody pays a little
     * through the money supply.
     */
    private void rescueIfInsolvent() {
        long equity = equity();
        // A bank with deposits and no borrowers loses a few gold a day to
        // deposit interest, and that is normal trading, not a failure. Only a
        // hole worth announcing gets filled - otherwise every quiet server
        // gets a bailout broadcast every twenty minutes.
        if (equity >= -config.bailoutThreshold()) {
            return;
        }
        long need = -equity;
        cash += need;
        recapitalised += need;
        if (plugin.macro() != null) {
            plugin.macro().recordPrinting(need);
        }
        plugin.getServer().broadcastMessage(ChatColor.DARK_RED + "[중앙은행] "
                + ChatColor.WHITE + "은행 자본이 바닥나 " + need + plugin.rpgConfig().goldSymbol()
                + " 를 발권해 메웠습니다. " + ChatColor.GRAY + "(통화량이 늘어 물가가 오릅니다)");
    }

    private void tell(BankAccount account, String message) {
        Player online = plugin.getServer().getPlayer(account.owner());
        if (online != null) {
            online.sendMessage(message);
        }
    }

    // ------------------------------------------------------------- utilities

    private boolean guard(Player player) {
        if (!enabled()) {
            player.sendMessage(ChatColor.RED + "[은행] 이 서버에서는 은행을 쓸 수 없습니다.");
            return false;
        }
        return true;
    }

    private int today() {
        return plugin.macro() == null ? 0 : plugin.macro().day();
    }

    public static String percent(double value) {
        return String.format(Locale.ROOT, "%.2f%%", value);
    }

    /** Net worth for the inequality measure: wallet plus savings minus debt. */
    public long netWorthOf(OfflinePlayer player, long wallet) {
        BankAccount account = peek(player.getUniqueId());
        if (account == null) {
            return wallet;
        }
        return wallet + account.totalDeposits() - account.totalDebt();
    }

    // ----------------------------------------------------------- persistence

    void restoreLedger(long cash, long centralBankLoans, long recapitalised, long writtenOff) {
        this.cash = Math.max(0, cash);
        this.centralBankLoans = Math.max(0, centralBankLoans);
        this.recapitalised = Math.max(0, recapitalised);
        this.writtenOff = Math.max(0, writtenOff);
    }

    void restoreAccount(BankAccount account) {
        accounts.put(account.owner(), account);
    }

    EconomyConfig config() {
        return config;
    }
}
