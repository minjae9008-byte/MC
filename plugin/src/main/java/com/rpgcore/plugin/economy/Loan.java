package com.rpgcore.plugin.economy;

import java.util.UUID;

/**
 * One loan, from the day it is handed over to the day it is settled.
 *
 * The rate is fixed at origination and never re-reads the policy rate. A
 * floating rate would mean the central bank raising rates could bankrupt
 * somebody who borrowed responsibly last week, and a player cannot hedge.
 * Rate risk sits with the bank, which is also who gets the spread.
 */
public final class Loan {

    private final UUID id;
    private final long principal;
    /** Principal plus interest accrued so far, minus anything repaid. */
    private double outstanding;
    private final double annualRate;
    private final int openedDay;
    private final int dueDay;
    private long repaid;
    private int overdueDays;

    public Loan(UUID id, long principal, double outstanding, double annualRate,
                int openedDay, int dueDay, long repaid, int overdueDays) {
        this.id = id;
        this.principal = principal;
        this.outstanding = outstanding;
        this.annualRate = annualRate;
        this.openedDay = openedDay;
        this.dueDay = dueDay;
        this.repaid = repaid;
        this.overdueDays = overdueDays;
    }

    public static Loan open(long principal, double annualRate, int day, int termDays) {
        return new Loan(UUID.randomUUID(), principal, principal, annualRate,
                day, day + termDays, 0, 0);
    }

    public UUID id() {
        return id;
    }

    public long principal() {
        return principal;
    }

    public double outstanding() {
        return outstanding;
    }

    /** Rounded up: a debt of 0.4 gold is still a debt, and must be payable. */
    public long owed() {
        return (long) Math.ceil(outstanding);
    }

    public double annualRate() {
        return annualRate;
    }

    public int openedDay() {
        return openedDay;
    }

    public int dueDay() {
        return dueDay;
    }

    public long repaid() {
        return repaid;
    }

    public int overdueDays() {
        return overdueDays;
    }

    public boolean overdue(int today) {
        return today > dueDay;
    }

    public int daysLeft(int today) {
        return dueDay - today;
    }

    /** Adds one day of interest and returns what it cost the borrower. */
    double accrue(double dailyRate) {
        double interest = outstanding * dailyRate;
        outstanding += interest;
        return interest;
    }

    void markOverdueDay() {
        overdueDays++;
    }

    /** Takes a payment off the balance and reports what was actually used. */
    long pay(long amount) {
        long used = (long) Math.min(amount, Math.ceil(outstanding));
        outstanding = Math.max(0, outstanding - used);
        repaid += used;
        return used;
    }

    public boolean settled() {
        return outstanding <= 0.0001;
    }
}
