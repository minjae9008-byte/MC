package com.rpgcore.plugin.economy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One player's side of the bank: a demand account, any term deposits, any
 * loans, and the credit record that decides what they can borrow next.
 *
 * Keyed by UUID and carrying the last name seen, so a rename follows the money
 * and the ledger can still be read by a human afterwards.
 *
 * The level is copied in rather than read live because interest, arrears and
 * credit recovery all run for players who are not online - the daily pass
 * cannot ask a Player object that does not exist.
 */
public final class BankAccount {

    private final UUID owner;
    private String name;
    private long checking;
    private final List<TimeDeposit> deposits = new ArrayList<>();
    private final List<Loan> loans = new ArrayList<>();

    private int creditScore;
    private int level = 1;
    private int loansTaken;
    private int loansRepaid;
    private int defaults;
    private long interestEarned;
    private long interestPaid;
    /** The last economic day this account's credit was updated. */
    private int lastTouchedDay;

    public BankAccount(UUID owner, String name, int creditScore) {
        this.owner = owner;
        this.name = name;
        this.creditScore = creditScore;
    }

    public UUID owner() {
        return owner;
    }

    public String name() {
        return name;
    }

    public void name(String name) {
        if (name != null && !name.isBlank()) {
            this.name = name;
        }
    }

    public long checking() {
        return checking;
    }

    void checking(long checking) {
        this.checking = Math.max(0, checking);
    }

    void addChecking(long amount) {
        this.checking = Math.max(0, this.checking + amount);
    }

    public List<TimeDeposit> deposits() {
        return deposits;
    }

    public List<Loan> loans() {
        return loans;
    }

    /** Demand plus term deposits: what the bank owes this player. */
    public long totalDeposits() {
        long total = checking;
        for (TimeDeposit deposit : deposits) {
            total += deposit.value();
        }
        return total;
    }

    public long totalDebt() {
        long total = 0;
        for (Loan loan : loans) {
            total += loan.owed();
        }
        return total;
    }

    public boolean hasOverdue(int today) {
        for (Loan loan : loans) {
            if (loan.overdue(today)) {
                return true;
            }
        }
        return false;
    }

    public int creditScore() {
        return creditScore;
    }

    void creditScore(int creditScore) {
        this.creditScore = Math.clamp(creditScore, 0, 1000);
    }

    void bumpCredit(int delta) {
        creditScore(creditScore + delta);
    }

    public int level() {
        return level;
    }

    public void level(int level) {
        this.level = Math.max(1, level);
    }

    public int loansTaken() {
        return loansTaken;
    }

    public int loansRepaid() {
        return loansRepaid;
    }

    public int defaults() {
        return defaults;
    }

    void countLoanTaken() {
        loansTaken++;
    }

    void countLoanRepaid() {
        loansRepaid++;
    }

    void countDefault() {
        defaults++;
    }

    public long interestEarned() {
        return interestEarned;
    }

    public long interestPaid() {
        return interestPaid;
    }

    void addInterestEarned(long amount) {
        interestEarned += amount;
    }

    void addInterestPaid(long amount) {
        interestPaid += amount;
    }

    public int lastTouchedDay() {
        return lastTouchedDay;
    }

    void lastTouchedDay(int day) {
        this.lastTouchedDay = day;
    }

    void restoreCounters(int loansTaken, int loansRepaid, int defaults,
                         long interestEarned, long interestPaid) {
        this.loansTaken = Math.max(0, loansTaken);
        this.loansRepaid = Math.max(0, loansRepaid);
        this.defaults = Math.max(0, defaults);
        this.interestEarned = Math.max(0, interestEarned);
        this.interestPaid = Math.max(0, interestPaid);
    }

    /** True when nothing here is worth writing to disk. */
    public boolean empty() {
        return checking == 0 && deposits.isEmpty() && loans.isEmpty()
                && loansTaken == 0 && interestEarned == 0 && interestPaid == 0;
    }
}
