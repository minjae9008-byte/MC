package com.rpgcore.plugin.economy;

import java.util.UUID;

/**
 * A term deposit: gold locked away for a fixed number of economic days at a
 * rate better than the demand account pays.
 *
 * The premium is the price of illiquidity - the bank can lend money it knows
 * will not be asked for tomorrow, so it pays more for it. Breaking the term
 * early gives the principal back but forfeits part of the interest, which is
 * what makes the promise worth anything to the bank.
 */
public final class TimeDeposit {

    private final UUID id;
    private final long principal;
    private final double annualRate;
    private final int openedDay;
    private final int termDays;
    private double accrued;

    public TimeDeposit(UUID id, long principal, double annualRate,
                       int openedDay, int termDays, double accrued) {
        this.id = id;
        this.principal = principal;
        this.annualRate = annualRate;
        this.openedDay = openedDay;
        this.termDays = termDays;
        this.accrued = accrued;
    }

    public static TimeDeposit open(long principal, double annualRate, int day, int termDays) {
        return new TimeDeposit(UUID.randomUUID(), principal, annualRate, day, termDays, 0);
    }

    public UUID id() {
        return id;
    }

    public long principal() {
        return principal;
    }

    public double annualRate() {
        return annualRate;
    }

    public int openedDay() {
        return openedDay;
    }

    public int termDays() {
        return termDays;
    }

    public int maturesOn() {
        return openedDay + termDays;
    }

    public boolean matured(int today) {
        return today >= maturesOn();
    }

    public int daysLeft(int today) {
        return Math.max(0, maturesOn() - today);
    }

    public double accrued() {
        return accrued;
    }

    /** Compounds one day onto the balance and returns the interest added. */
    double accrue(double dailyRate) {
        double interest = (principal + accrued) * dailyRate;
        accrued += interest;
        return interest;
    }

    public long value() {
        return principal + (long) Math.floor(accrued);
    }
}
