package com.rpgcore.plugin.economy;

import org.bukkit.Material;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * One tradable good, and everything the market knows about it.
 *
 * Two numbers are derived rather than configured, and both come from the same
 * place - how fast the thing can be dug up:
 *
 *   기준가  basePrice = (시간당 임금 / 시간당 채굴량) x 수요계수
 *           The labour theory of value, applied literally. An hour spent
 *           mining is worth an hour spent farming, so a good you get 18 of in
 *           an hour is worth 23 times one you get 420 of. This is why the
 *           config file has no prices in it: prices that are typed in by hand
 *           drift out of line with how hard things actually are to get, and
 *           then the whole server farms the one item that was mispriced.
 *
 *   기준재고 baseStock = 시간당 채굴량 x 시간 x 인원
 *           The initial supply, and also the level supply reverts to. Common
 *           things start piled up, rare things start scarce, in exactly the
 *           proportion that the world hands them out.
 *
 * The live price then moves around basePrice with supply:
 *
 *   price = basePrice x (baseStock / stock) ^ (1 / elasticity) x sentiment
 *
 * The exponent is the reciprocal of the price elasticity of demand, which is
 * what makes staples swing harder than luxuries: when bread runs short people
 * still buy bread (inelastic, big price move), when diamonds run short people
 * simply do without (elastic, small price move).
 */
public final class MarketItem {

    /** Bars for the price chart, low to high. */
    private static final char[] SPARK = {'▁', '▂', '▃', '▄', '▅', '▆', '▇', '█'};

    private final Material material;
    private final String id;
    private final String category;
    private final double perHour;
    private final double demand;
    private final double elasticity;
    private final double basket;

    private double basePrice;
    private double baseStock;

    private double stock;
    /**
     * Everything that moves the price but is not supply: the daily random
     * walk, and whatever shock is currently working through. Mean-reverts to
     * 1.0, or a good that once spiked would stay expensive forever.
     */
    private double sentiment = 1.0;
    /**
     * The economy-wide price level, the same number on every good.
     *
     * Separate from sentiment because the two behave differently: sentiment
     * is this good's own mood and reverts to 1.0 within days, while the price
     * level is what money has done to prices and does not revert at all. Mix
     * them and the daily reversion quietly undoes inflation, which is how a
     * model ends up with a money supply that has no effect on anything.
     */
    private double priceLevel = 1.0;

    private double price;
    /** The close of the previous economic day, for the change column. */
    private double previousClose;
    private final Deque<Double> history = new ArrayDeque<>();

    private long boughtToday;
    private long soldToday;
    private long lifetimeBought;
    private long lifetimeSold;
    /** What the last shock was, for the tooltip. Null once it has decayed. */
    private String shockNote;

    MarketItem(EconomyConfig.ItemDef def, EconomyConfig config) {
        this.material = def.material();
        this.id = def.material().getKey().getKey().toLowerCase(Locale.ROOT);
        this.category = def.category();
        this.perHour = def.perHour();
        this.demand = def.demand();
        this.elasticity = def.elasticity();
        this.basket = def.basket();
        applyConfig(config);
        this.stock = baseStock;
        this.price = mid();
        this.previousClose = price;
    }

    /**
     * Recomputes the derived constants after a reload.
     *
     * Live stock is deliberately left alone: an operator who raises the wage
     * is repricing the world, not confiscating what is in the warehouse.
     */
    void applyConfig(EconomyConfig config) {
        this.basePrice = Math.max(0.01, config.hourlyWage() / perHour * demand);
        this.baseStock = Math.max(1.0,
                perHour * config.initialSupplyHours() * config.referencePlayers());
    }

    // ----------------------------------------------------------------- price

    /**
     * The mid price at a hypothetical stock level.
     *
     * Taking stock as an argument rather than reading the field is what makes
     * a large order cost more than a small one: the caller walks the curve one
     * unit at a time, so buying out the warehouse moves the price while you
     * are buying it, exactly as it would in a real order book.
     */
    public double midAt(double atStock, EconomyConfig config) {
        double exponent = 1.0 / elasticity;
        double ratio = Math.pow(baseStock / Math.max(1.0, atStock), exponent);
        ratio = Math.clamp(ratio, config.minPriceRatio(), config.maxPriceRatio());
        return Math.max(0.01, basePrice * ratio * sentiment * priceLevel);
    }

    /** The current mid price, cached by the daily recompute and by each trade. */
    public double mid() {
        return price;
    }

    void recompute(EconomyConfig config) {
        this.price = midAt(stock, config);
    }

    /** What a buyer pays per unit: mid plus half the spread. */
    public double ask(EconomyConfig config) {
        return price * (1.0 + config.spreadPercent() / 200.0);
    }

    /** What a seller receives per unit: mid minus half the spread. */
    public double bid(EconomyConfig config) {
        return price * (1.0 - config.spreadPercent() / 200.0);
    }

    public double askAt(double atStock, EconomyConfig config) {
        return midAt(atStock, config) * (1.0 + config.spreadPercent() / 200.0);
    }

    public double bidAt(double atStock, EconomyConfig config) {
        return midAt(atStock, config) * (1.0 - config.spreadPercent() / 200.0);
    }

    /** Day-on-day change, in percent. */
    public double changePercent() {
        if (previousClose <= 0) {
            return 0;
        }
        return (price - previousClose) / previousClose * 100.0;
    }

    /** Price against its own base, in percent. Above 100 means dear. */
    public double valuationPercent() {
        return basePrice <= 0 ? 100 : price / basePrice * 100.0;
    }

    /** Stock against the level it reverts to, in percent. */
    public double supplyPercent() {
        return baseStock <= 0 ? 100 : stock / baseStock * 100.0;
    }

    /** The whole warehouse at the mid price - this good's share of the market. */
    public double marketCap() {
        return stock * price;
    }

    // ------------------------------------------------------------- the chart

    /**
     * The price history as eight-level bars, scaled to its own range.
     *
     * Scaled to the range rather than to zero on purpose: a good that moved
     * between 104 and 106 should look like it moved, and against a zero
     * baseline every chart on the screen would be a flat line at the top.
     */
    public String sparkline() {
        return spark(history);
    }

    /** The same chart for any series - the price index uses it too. */
    public static String spark(Iterable<Double> points) {
        double low = Double.MAX_VALUE;
        double high = -Double.MAX_VALUE;
        int count = 0;
        for (double point : points) {
            low = Math.min(low, point);
            high = Math.max(high, point);
            count++;
        }
        if (count < 2) {
            return "";
        }
        StringBuilder out = new StringBuilder(count);
        double span = high - low;
        for (double point : points) {
            int level = span <= 0.0000001
                    ? SPARK.length / 2
                    : (int) Math.round((point - low) / span * (SPARK.length - 1));
            out.append(SPARK[Math.clamp(level, 0, SPARK.length - 1)]);
        }
        return out.toString();
    }

    public List<Double> history() {
        return new ArrayList<>(history);
    }

    void pushHistory(int limit) {
        history.addLast(price);
        while (history.size() > limit) {
            history.removeFirst();
        }
    }

    void restoreHistory(List<Double> points, int limit) {
        history.clear();
        for (double point : points) {
            history.addLast(point);
        }
        while (history.size() > limit) {
            history.removeFirst();
        }
    }

    // ------------------------------------------------------------------ state

    public Material material() {
        return material;
    }

    public String id() {
        return id;
    }

    public String category() {
        return category;
    }

    public double perHour() {
        return perHour;
    }

    public double demand() {
        return demand;
    }

    public double elasticity() {
        return elasticity;
    }

    public double basket() {
        return basket;
    }

    public double basePrice() {
        return basePrice;
    }

    public double baseStock() {
        return baseStock;
    }

    public double stock() {
        return stock;
    }

    void stock(double stock) {
        this.stock = Math.max(0, stock);
    }

    public double sentiment() {
        return sentiment;
    }

    public double priceLevel() {
        return priceLevel;
    }

    void priceLevel(double priceLevel) {
        this.priceLevel = Math.clamp(priceLevel, 0.05, 50.0);
    }

    void sentiment(double sentiment) {
        // Bounded hard: a random walk with no bounds eventually finds a number
        // that overflows the gold column when multiplied by a stack of 64.
        this.sentiment = Math.clamp(sentiment, 0.1, 10.0);
    }

    void price(double price) {
        this.price = price;
    }

    public double previousClose() {
        return previousClose;
    }

    void previousClose(double previousClose) {
        this.previousClose = previousClose;
    }

    public long boughtToday() {
        return boughtToday;
    }

    public long soldToday() {
        return soldToday;
    }

    public long lifetimeBought() {
        return lifetimeBought;
    }

    public long lifetimeSold() {
        return lifetimeSold;
    }

    void countBought(long units) {
        boughtToday += units;
        lifetimeBought += units;
    }

    void countSold(long units) {
        soldToday += units;
        lifetimeSold += units;
    }

    void restoreVolume(long lifetimeBought, long lifetimeSold) {
        this.lifetimeBought = Math.max(0, lifetimeBought);
        this.lifetimeSold = Math.max(0, lifetimeSold);
    }

    void closeDay() {
        previousClose = price;
        boughtToday = 0;
        soldToday = 0;
    }

    public String shockNote() {
        return shockNote;
    }

    void shockNote(String shockNote) {
        this.shockNote = shockNote;
    }

    /** Whether today's trading was net buying (demand pressure) or selling. */
    public long netFlowToday() {
        return boughtToday - soldToday;
    }
}
