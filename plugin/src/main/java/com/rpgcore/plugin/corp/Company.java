package com.rpgcore.plugin.corp;

import org.bukkit.Material;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A company: people, plants, stock in a warehouse, and stock on an exchange.
 *
 * Two things are worth knowing before reading the rest.
 *
 * <b>Shares are held in a map, and the company can appear in its own map.</b>
 * Shares the company holds are treasury shares - the float that has not been
 * sold yet, and where bought-back shares go. That one trick removes the need
 * for an order book: an investor always trades with the company itself, and
 * the company's own cash is the liquidity. A company with no cash cannot buy
 * its shares back, which is a real risk an investor can read off the screen
 * rather than a rule invented to be annoying.
 *
 * <b>A holder can be another company.</b> Holder keys are plain UUIDs, and a
 * company id is a UUID, so a takeover is just shares moving to a holder that
 * happens to be a company. Dividends follow the same path, which is what
 * makes a parent company actually earn from a subsidiary.
 */
public final class Company {

    /** What somebody may do. The CEO is stored as an employee too. */
    public enum Role {
        CEO("대표"), DIRECTOR("임원"), STAFF("직원");

        private final String label;

        Role(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        /** Directors and the CEO may spend company money. */
        public boolean manages() {
            return this != STAFF;
        }
    }

    public static final class Employee {
        private String name;
        private Role role;
        private int unpaidDays;
        private final long joinedAt;

        public Employee(String name, Role role, int unpaidDays, long joinedAt) {
            this.name = name;
            this.role = role;
            this.unpaidDays = unpaidDays;
            this.joinedAt = joinedAt;
        }

        public String name() {
            return name;
        }

        public void name(String name) {
            if (name != null && !name.isBlank()) {
                this.name = name;
            }
        }

        public Role role() {
            return role;
        }

        public void role(Role role) {
            this.role = role;
        }

        public int unpaidDays() {
            return unpaidDays;
        }

        void unpaidDays(int unpaidDays) {
            this.unpaidDays = Math.max(0, unpaidDays);
        }

        public long joinedAt() {
            return joinedAt;
        }
    }

    /**
     * The investing public: the holder that owns a seeded company's float
     * before any player does.
     *
     * Without it a seeded company held every one of its own shares, which
     * made it ownerless - and a company nobody owns can be absorbed by
     * anybody for nothing. That was found by doing exactly that in testing:
     * a 257,000-gold fishery was swallowed whole for free. The public holds
     * the float instead, so taking a company over means buying it from
     * somebody, at a price.
     */
    public static final UUID PUBLIC = UUID.fromString("00000000-0000-0000-0000-0000000000ff");

    private final UUID id;
    private String name;
    private String ticker;
    private UUID ceo;
    private final boolean npc;
    private final long createdAt;

    private final Map<UUID, Employee> employees = new LinkedHashMap<>();
    private long cash;
    private final Map<Material, Long> warehouse = new EnumMap<>(Material.class);
    private final List<Factory> factories = new ArrayList<>();

    private long sharesIssued;
    /** Holder -> shares. The company's own id is its treasury. */
    private final Map<UUID, Long> holders = new LinkedHashMap<>();

    private int sellPercent;
    private boolean autoBuyInputs;
    private int dividendPercent;

    private double sharePrice;
    private double sentiment = 1.0;
    private final Deque<Double> priceHistory = new ArrayDeque<>();

    private long lastRevenue;
    private long lastCosts;
    private long lastWages;
    private long lastDividends;
    private long lastProfit;
    private long lifetimeRevenue;
    private long lifetimeCosts;
    private long lifetimeDividends;
    private int daysOperating;
    /** Cash already spent buying shares back today; reset each economic day. */
    private long buybackSpentToday;
    private boolean bankrupt;

    public Company(UUID id, String name, String ticker, UUID ceo, boolean npc, long createdAt) {
        this.id = id;
        this.name = name;
        this.ticker = ticker;
        this.ceo = ceo;
        this.npc = npc;
        this.createdAt = createdAt;
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public void name(String name) {
        this.name = name;
    }

    public String ticker() {
        return ticker;
    }

    public void ticker(String ticker) {
        this.ticker = ticker;
    }

    /** Null for a seeded company, which nobody runs. */
    public UUID ceo() {
        return ceo;
    }

    public void ceo(UUID ceo) {
        this.ceo = ceo;
    }

    public boolean npc() {
        return npc;
    }

    public long createdAt() {
        return createdAt;
    }

    public boolean bankrupt() {
        return bankrupt;
    }

    void bankrupt(boolean bankrupt) {
        this.bankrupt = bankrupt;
    }

    // ------------------------------------------------------------- people

    public Map<UUID, Employee> employees() {
        return employees;
    }

    public Employee employee(UUID uuid) {
        return employees.get(uuid);
    }

    public boolean employs(UUID uuid) {
        return employees.containsKey(uuid);
    }

    public boolean manages(UUID uuid) {
        Employee employee = employees.get(uuid);
        return employee != null && employee.role().manages();
    }

    public int headcount() {
        return employees.size();
    }

    // -------------------------------------------------------------- money

    public long cash() {
        return cash;
    }

    /** Deliberately allowed to go negative: that is what a debt looks like. */
    public void cash(long cash) {
        this.cash = cash;
    }

    public void addCash(long amount) {
        this.cash += amount;
    }

    public long buybackSpentToday() {
        return buybackSpentToday;
    }

    void buybackSpentToday(long buybackSpentToday) {
        this.buybackSpentToday = buybackSpentToday;
    }

    // ---------------------------------------------------------- warehouse

    public Map<Material, Long> warehouse() {
        return warehouse;
    }

    public long stockOf(Material material) {
        return warehouse.getOrDefault(material, 0L);
    }

    public void addStock(Material material, long amount) {
        if (amount == 0) {
            return;
        }
        long next = stockOf(material) + amount;
        if (next <= 0) {
            warehouse.remove(material);
        } else {
            warehouse.put(material, next);
        }
    }

    public List<Factory> factories() {
        return factories;
    }

    // ------------------------------------------------------------- shares

    public long sharesIssued() {
        return sharesIssued;
    }

    void sharesIssued(long sharesIssued) {
        this.sharesIssued = Math.max(0, sharesIssued);
    }

    public Map<UUID, Long> holders() {
        return holders;
    }

    public long sharesOf(UUID holder) {
        return holders.getOrDefault(holder, 0L);
    }

    /** Shares the company holds itself: the float still for sale. */
    public long treasuryShares() {
        return sharesOf(id);
    }

    /**
     * Shares actually in issue - everything except what the company holds
     * itself.
     *
     * This is the number that prices a share, because treasury shares are
     * not a claim on anything: selling one into the market at fair value
     * leaves every other holder's stake worth exactly what it was.
     */
    public long outstandingShares() {
        return Math.max(0, sharesIssued - treasuryShares());
    }

    /** What dividends are paid on. Same set as {@link #outstandingShares()}. */
    public long publicShares() {
        return outstandingShares();
    }

    public void moveShares(UUID from, UUID to, long amount) {
        if (amount <= 0) {
            return;
        }
        long have = sharesOf(from);
        long moved = Math.min(have, amount);
        if (moved <= 0) {
            return;
        }
        if (have - moved <= 0) {
            holders.remove(from);
        } else {
            holders.put(from, have - moved);
        }
        holders.merge(to, moved, Long::sum);
    }

    /** New shares straight into the treasury. Dilutes everybody else. */
    void issue(long amount) {
        if (amount <= 0) {
            return;
        }
        sharesIssued += amount;
        holders.merge(id, amount, Long::sum);
    }

    void setHolding(UUID holder, long amount) {
        if (amount <= 0) {
            holders.remove(holder);
        } else {
            holders.put(holder, amount);
        }
    }

    /** True when one holder has more than half of everything issued. */
    public UUID controllingHolder() {
        long outstanding = outstandingShares();
        for (Map.Entry<UUID, Long> entry : holders.entrySet()) {
            // The company's own holding is not a vote, and the public never
            // turns up to one.
            if (entry.getKey().equals(id) || entry.getKey().equals(PUBLIC)) {
                continue;
            }
            if (entry.getValue() * 2 > outstanding) {
                return entry.getKey();
            }
        }
        return null;
    }

    // ------------------------------------------------------------ policy

    public int sellPercent() {
        return sellPercent;
    }

    public void sellPercent(int sellPercent) {
        this.sellPercent = Math.clamp(sellPercent, 0, 100);
    }

    public boolean autoBuyInputs() {
        return autoBuyInputs;
    }

    public void autoBuyInputs(boolean autoBuyInputs) {
        this.autoBuyInputs = autoBuyInputs;
    }

    public int dividendPercent() {
        return dividendPercent;
    }

    public void dividendPercent(int dividendPercent) {
        this.dividendPercent = Math.clamp(dividendPercent, 0, 100);
    }

    // ------------------------------------------------------------- price

    public double sharePrice() {
        return sharePrice;
    }

    public void sharePrice(double sharePrice) {
        this.sharePrice = Math.max(0, sharePrice);
    }

    public double sentiment() {
        return sentiment;
    }

    public void sentiment(double sentiment) {
        // Bounded for the same reason the goods market bounds its own: an
        // unbounded walk eventually produces a number that overflows a price.
        this.sentiment = Math.clamp(sentiment, 0.1, 10.0);
    }

    public List<Double> priceHistory() {
        return new ArrayList<>(priceHistory);
    }

    public void pushPrice(int limit) {
        priceHistory.addLast(sharePrice);
        while (priceHistory.size() > limit) {
            priceHistory.removeFirst();
        }
    }

    public void restorePriceHistory(List<Double> points, int limit) {
        priceHistory.clear();
        for (double point : points) {
            priceHistory.addLast(point);
        }
        while (priceHistory.size() > limit) {
            priceHistory.removeFirst();
        }
    }

    // --------------------------------------------------------- financials

    public long lastRevenue() {
        return lastRevenue;
    }

    public long lastCosts() {
        return lastCosts;
    }

    public long lastWages() {
        return lastWages;
    }

    public long lastDividends() {
        return lastDividends;
    }

    public long lastProfit() {
        return lastProfit;
    }

    public long lifetimeRevenue() {
        return lifetimeRevenue;
    }

    public long lifetimeCosts() {
        return lifetimeCosts;
    }

    public long lifetimeDividends() {
        return lifetimeDividends;
    }

    public int daysOperating() {
        return daysOperating;
    }

    void recordDay(long revenue, long costs, long wages, long profit) {
        this.lastRevenue = revenue;
        this.lastCosts = costs;
        this.lastWages = wages;
        this.lastProfit = profit;
        this.lifetimeRevenue += Math.max(0, revenue);
        this.lifetimeCosts += Math.max(0, costs);
        this.daysOperating++;
    }

    void recordDividend(long paid) {
        this.lastDividends = paid;
        this.lifetimeDividends += Math.max(0, paid);
    }

    void restoreFinancials(long lastRevenue, long lastCosts, long lastWages, long lastProfit,
                           long lifetimeRevenue, long lifetimeCosts, long lifetimeDividends,
                           int daysOperating) {
        this.lastRevenue = lastRevenue;
        this.lastCosts = lastCosts;
        this.lastWages = lastWages;
        this.lastProfit = lastProfit;
        this.lifetimeRevenue = Math.max(0, lifetimeRevenue);
        this.lifetimeCosts = Math.max(0, lifetimeCosts);
        this.lifetimeDividends = Math.max(0, lifetimeDividends);
        this.daysOperating = Math.max(0, daysOperating);
    }
}
