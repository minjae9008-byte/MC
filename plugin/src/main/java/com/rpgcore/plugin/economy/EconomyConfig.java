package com.rpgcore.plugin.economy;

import com.rpgcore.plugin.RpgCorePlugin;
import com.rpgcore.plugin.config.ConfigFiles;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Typed view over economy.yml - the market, the bank and the central bank.
 *
 * Kept out of {@link com.rpgcore.plugin.config.RpgConfig} on purpose. That
 * class is the operator's everyday knobs; this is a model with about sixty
 * parameters that only make sense read together, and it ships its own file
 * the way jobs and achievements do.
 *
 * Everything is read once per reload into fields, because the pricing loop
 * touches these values for every item on every economic day.
 */
public final class EconomyConfig {

    public static final String FILE = "economy.yml";

    /**
     * One tradable good, as the file declares it.
     *
     * Note what is NOT here: a price. Both the base price and the starting
     * supply are derived from {@code perHour}, which is the whole idea - an
     * operator says how fast a thing can be dug up and the economy works out
     * what it is worth and how much of it exists.
     */
    public record ItemDef(Material material, String category, double perHour,
                          double demand, double elasticity, double basket) {
    }

    /** A credit grade: what it is called, what it lends, what it costs. */
    public record Grade(String name, int minScore, double limitMultiplier, double riskPremium) {
    }

    public record Category(String id, String name, Material icon) {
    }

    private final RpgCorePlugin plugin;

    // --- clock ---
    private int dayMinutes;
    private int daysPerYear;

    // --- market ---
    private String marketTitle;
    private double hourlyWage;
    private int initialSupplyHours;
    private int referencePlayers;
    private double minPriceRatio;
    private double maxPriceRatio;
    private double maxStockMultiple;
    private double spreadPercent;
    private double salesTaxPercent;
    private int maxUnitsPerOrder;
    private double restockPercentPerDay;
    private double productionWeight;
    private double volatilityPercent;
    private double sentimentReversionPercent;
    private boolean shockEnabled;
    private int shockChancePercent;
    private double shockMinPercent;
    private double shockMaxPercent;
    private double shockDecayPercent;
    private long treasuryStart;
    private long treasuryFloor;
    private long treasuryRefill;
    private int historyPoints;
    private boolean announceShocks;
    private boolean dailyReport;

    // --- bank ---
    private String bankTitle;
    private double reserveRatioPercent;
    private double depositMarginPercent;
    private double loanMarginPercent;
    private double termPremiumPerDayPercent;
    private int termMinDays;
    private int termMaxDays;
    private double earlyWithdrawalPenaltyPercent;
    private long centralBankFacility;
    private long bailoutThreshold;
    private int loanMin;
    private int loanMaxDays;
    private int loanDefaultDays;
    private double overdueExtraPercent;
    private int overdueCreditDropPerDay;
    private double originationFeePercent;
    private int maxLoans;
    private int limitBase;
    private int limitPerLevel;
    private double limitDepositPercent;
    private double dtiPercent;
    private int creditStart;
    private int creditOnRepay;
    private int creditOnDefault;
    private int creditRecoveryPerDay;
    private double garnishPercent;
    private final List<Grade> grades = new ArrayList<>();

    // --- macro ---
    private double inflationTargetPercent;
    private double neutralRatePercent;
    private double taylorInflationWeight;
    private double taylorOutputWeight;
    private double rateFloorPercent;
    private double rateCeilingPercent;
    private double rateMoveMaxPercent;
    private boolean openMarketOperations;
    private double omoBandPercent;
    private int inflationMinDays;
    private double inflationSmoothing;
    private double omoMaxSharePercent;
    private double gdpSmoothingPercent;
    private double moneyPassThroughPercent;
    private double maxPriceDriftPercent;
    private boolean broadcastPolicyChange;

    private final List<ItemDef> items = new ArrayList<>();
    private final Map<String, Category> categories = new LinkedHashMap<>();

    public EconomyConfig(RpgCorePlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        FileConfiguration c = ConfigFiles.load(plugin, FILE);

        dayMinutes = Math.clamp(c.getInt("clock.day-minutes", 20), 1, 1440);
        daysPerYear = Math.clamp(c.getInt("clock.days-per-year", 12), 1, 365);

        marketTitle = c.getString("market.title", "&8시장");
        hourlyWage = Math.max(1.0, c.getDouble("market.hourly-wage", 1200));
        initialSupplyHours = Math.clamp(c.getInt("market.initial-supply-hours", 24), 1, 8760);
        referencePlayers = Math.clamp(c.getInt("market.reference-players", 10), 1, 10000);
        minPriceRatio = Math.clamp(c.getDouble("market.min-price-ratio", 0.2), 0.01, 1.0);
        maxPriceRatio = Math.max(minPriceRatio * 2, c.getDouble("market.max-price-ratio", 8.0));
        maxStockMultiple = Math.max(2.0, c.getDouble("market.max-stock-multiple", 20));
        // A zero spread is a money printer: buy from the market, sell it back,
        // repeat. One percent is the floor rather than zero for that reason.
        spreadPercent = Math.clamp(c.getDouble("market.spread-percent", 6), 1.0, 60.0);
        salesTaxPercent = Math.clamp(c.getDouble("market.sales-tax-percent", 5), 0.0, 90.0);
        maxUnitsPerOrder = Math.clamp(c.getInt("market.max-units-per-order", 2048), 1, 100_000);
        restockPercentPerDay = Math.clamp(c.getDouble("market.restock-percent-per-day", 25), 0.0, 100.0);
        productionWeight = Math.clamp(c.getDouble("market.production-weight", 1.0), 0.0, 100.0);
        volatilityPercent = Math.clamp(c.getDouble("market.volatility-percent", 3), 0.0, 50.0);
        sentimentReversionPercent = Math.clamp(c.getDouble("market.sentiment-reversion-percent", 25), 0.0, 100.0);
        shockEnabled = c.getBoolean("market.shock.enabled", true);
        shockChancePercent = Math.clamp(c.getInt("market.shock.chance-percent-per-day", 35), 0, 100);
        shockMinPercent = Math.clamp(c.getDouble("market.shock.min-magnitude-percent", 15), 1.0, 95.0);
        shockMaxPercent = Math.max(shockMinPercent, c.getDouble("market.shock.max-magnitude-percent", 45));
        shockDecayPercent = Math.clamp(c.getDouble("market.shock.decay-percent-per-day", 30), 1.0, 100.0);
        treasuryStart = Math.max(0L, c.getLong("market.treasury-start", 3_000_000L));
        treasuryFloor = Math.max(0L, c.getLong("market.treasury-floor", 50_000L));
        treasuryRefill = Math.max(1L, c.getLong("market.treasury-refill", 500_000L));
        historyPoints = Math.clamp(c.getInt("market.history-points", 24), 2, 256);
        announceShocks = c.getBoolean("market.announce-shocks", true);
        dailyReport = c.getBoolean("market.daily-report", true);

        bankTitle = c.getString("bank.title", "&8은행");
        // Never zero: a bank that keeps no reserves lends out every coin it
        // holds, and the first withdrawal it cannot honour is a bank run.
        reserveRatioPercent = Math.clamp(c.getDouble("bank.reserve-ratio-percent", 20), 1.0, 100.0);
        depositMarginPercent = Math.clamp(c.getDouble("bank.deposit-margin-percent", 1.5), 0.0, 50.0);
        loanMarginPercent = Math.clamp(c.getDouble("bank.loan-margin-percent", 3.0), 0.0, 50.0);
        termPremiumPerDayPercent = Math.clamp(c.getDouble("bank.term-premium-per-day-percent", 0.4), 0.0, 10.0);
        termMinDays = Math.clamp(c.getInt("bank.term-min-days", 1), 1, 365);
        termMaxDays = Math.clamp(c.getInt("bank.term-max-days", 30), termMinDays, 365);
        earlyWithdrawalPenaltyPercent = Math.clamp(c.getDouble("bank.early-withdrawal-penalty-percent", 50), 0.0, 100.0);
        centralBankFacility = Math.max(0, c.getLong("bank.central-bank-facility", 200_000L));
        bailoutThreshold = Math.max(0, c.getLong("bank.bailout-threshold", 2_000L));
        loanMin = Math.max(1, c.getInt("bank.loan-min", 100));
        loanMaxDays = Math.clamp(c.getInt("bank.loan-max-days", 30), 1, 365);
        loanDefaultDays = Math.clamp(c.getInt("bank.loan-default-days", 30), 1, 365);
        overdueExtraPercent = Math.clamp(c.getDouble("bank.overdue-extra-percent", 15), 0.0, 100.0);
        overdueCreditDropPerDay = Math.clamp(c.getInt("bank.overdue-credit-drop-per-day", 12), 0, 1000);
        originationFeePercent = Math.clamp(c.getDouble("bank.origination-fee-percent", 1), 0.0, 50.0);
        maxLoans = Math.clamp(c.getInt("bank.max-loans", 3), 1, 20);
        limitBase = Math.max(0, c.getInt("bank.limit-base", 2000));
        limitPerLevel = Math.max(0, c.getInt("bank.limit-per-level", 400));
        limitDepositPercent = Math.clamp(c.getDouble("bank.limit-deposit-percent", 80), 0.0, 500.0);
        dtiPercent = Math.clamp(c.getDouble("bank.dti-percent", 250), 10.0, 2000.0);
        creditStart = Math.clamp(c.getInt("bank.credit-start", 600), 0, 1000);
        creditOnRepay = Math.clamp(c.getInt("bank.credit-on-repay", 18), 0, 500);
        creditOnDefault = Math.clamp(c.getInt("bank.credit-on-default", -180), -1000, 0);
        creditRecoveryPerDay = Math.clamp(c.getInt("bank.credit-recovery-per-day", 2), 0, 100);
        garnishPercent = Math.clamp(c.getDouble("bank.garnish-percent", 30), 0.0, 100.0);
        loadGrades(c);

        inflationTargetPercent = c.getDouble("macro.inflation-target-percent", 2.0);
        neutralRatePercent = c.getDouble("macro.neutral-rate-percent", 3.0);
        taylorInflationWeight = Math.max(0.0, c.getDouble("macro.taylor-inflation-weight", 0.5));
        taylorOutputWeight = Math.max(0.0, c.getDouble("macro.taylor-output-weight", 0.25));
        rateFloorPercent = Math.max(0.0, c.getDouble("macro.rate-floor-percent", 0.5));
        rateCeilingPercent = Math.max(rateFloorPercent + 0.5, c.getDouble("macro.rate-ceiling-percent", 40.0));
        rateMoveMaxPercent = Math.max(0.1, c.getDouble("macro.rate-move-max-percent", 1.0));
        openMarketOperations = c.getBoolean("macro.open-market-operations", true);
        omoBandPercent = Math.max(0.1, c.getDouble("macro.omo-band-percent", 5.0));
        inflationMinDays = Math.clamp(c.getInt("macro.inflation-min-days", 3), 1, 90);
        inflationSmoothing = Math.clamp(
                c.getDouble("macro.inflation-smoothing-percent", 40), 1.0, 100.0) / 100.0;
        omoMaxSharePercent = Math.clamp(c.getDouble("macro.omo-max-share-percent", 4.0), 0.1, 50.0);
        gdpSmoothingPercent = Math.clamp(c.getDouble("macro.gdp-smoothing-percent", 20), 1.0, 100.0);
        moneyPassThroughPercent = Math.clamp(
                c.getDouble("macro.money-pass-through-percent", 60), 0.0, 200.0);
        maxPriceDriftPercent = Math.clamp(
                c.getDouble("macro.max-price-drift-percent", 3.0), 0.0, 50.0);
        broadcastPolicyChange = c.getBoolean("macro.broadcast-policy-change", true);

        loadCategories(c);
        loadItems(c);
    }

    private void loadGrades(FileConfiguration c) {
        grades.clear();
        for (Map<?, ?> row : c.getMapList("bank.grades")) {
            Object rawName = row.get("name");
            String name = rawName == null ? "?" : String.valueOf(rawName);
            int minScore = number(row.get("min-score"), 0).intValue();
            double multiplier = number(row.get("limit-multiplier"), 1.0).doubleValue();
            double premium = number(row.get("risk-premium"), 0.0).doubleValue();
            grades.add(new Grade(name, minScore, Math.max(0.0, multiplier), Math.max(0.0, premium)));
        }
        // Sorted high to low so the first match wins, whatever order the file
        // lists them in - an operator adding a grade should not have to know
        // that the lookup walks the list.
        grades.sort((a, b) -> Integer.compare(b.minScore(), a.minScore()));
        if (grades.isEmpty()) {
            grades.add(new Grade("C", 0, 1.0, 4.5));
        }
    }

    private void loadCategories(FileConfiguration c) {
        categories.clear();
        ConfigurationSection root = c.getConfigurationSection("categories");
        if (root == null) {
            return;
        }
        for (String id : root.getKeys(false)) {
            ConfigurationSection node = root.getConfigurationSection(id);
            if (node == null) {
                continue;
            }
            Material icon = Material.matchMaterial(node.getString("icon", "minecraft:paper"));
            categories.put(id.toLowerCase(Locale.ROOT), new Category(id.toLowerCase(Locale.ROOT),
                    node.getString("name", id), icon == null ? Material.PAPER : icon));
        }
    }

    private void loadItems(FileConfiguration c) {
        items.clear();
        ConfigurationSection root = c.getConfigurationSection("items");
        if (root == null) {
            plugin.getLogger().warning(FILE + ": no items section - the market will be empty.");
            return;
        }
        int skipped = 0;
        for (String key : root.getKeys(false)) {
            ConfigurationSection node = root.getConfigurationSection(key);
            if (node == null) {
                continue;
            }
            Material material = Material.matchMaterial(key);
            if (material == null || material.isAir()) {
                // A server version without this item, or a typo. Warned rather
                // than fatal: the rest of the market is still a market.
                plugin.getLogger().warning(FILE + ": unknown item '" + key + "' - skipped.");
                skipped++;
                continue;
            }
            double perHour = node.getDouble("per-hour", 0);
            if (perHour <= 0) {
                plugin.getLogger().warning(FILE + ": '" + key + "' has no per-hour rate - skipped."
                        + " Both its price and its supply are derived from that number.");
                skipped++;
                continue;
            }
            items.add(new ItemDef(material,
                    node.getString("category", "material").toLowerCase(Locale.ROOT),
                    perHour,
                    Math.max(0.01, node.getDouble("demand", 1.0)),
                    Math.clamp(node.getDouble("elasticity", 1.0), 0.2, 5.0),
                    Math.max(0.0, node.getDouble("basket", 0))));
        }
        if (skipped > 0) {
            plugin.getLogger().warning(FILE + ": " + skipped + " item(s) skipped.");
        }
    }

    private static Number number(Object value, Number fallback) {
        return value instanceof Number n ? n : fallback;
    }

    /** The grade a score falls into. Never null - the last grade catches all. */
    public Grade gradeFor(int score) {
        for (Grade grade : grades) {
            if (score >= grade.minScore()) {
                return grade;
            }
        }
        return grades.get(grades.size() - 1);
    }

    public List<Grade> grades() {
        return List.copyOf(grades);
    }

    public List<ItemDef> items() {
        return List.copyOf(items);
    }

    public Category category(String id) {
        return categories.get(id == null ? "" : id.toLowerCase(Locale.ROOT));
    }

    public List<Category> categories() {
        return List.copyOf(categories.values());
    }

    public int dayMinutes() {
        return dayMinutes;
    }

    public long dayMillis() {
        return dayMinutes * 60_000L;
    }

    public int daysPerYear() {
        return daysPerYear;
    }

    /** An annual percentage as the fraction that accrues in one economic day. */
    public double dailyFrom(double annualPercent) {
        return annualPercent / 100.0 / daysPerYear;
    }

    public String marketTitle() {
        return marketTitle;
    }

    public double hourlyWage() {
        return hourlyWage;
    }

    public int initialSupplyHours() {
        return initialSupplyHours;
    }

    public int referencePlayers() {
        return referencePlayers;
    }

    public double minPriceRatio() {
        return minPriceRatio;
    }

    public double maxPriceRatio() {
        return maxPriceRatio;
    }

    public double maxStockMultiple() {
        return maxStockMultiple;
    }

    public double spreadPercent() {
        return spreadPercent;
    }

    public double salesTaxPercent() {
        return salesTaxPercent;
    }

    public int maxUnitsPerOrder() {
        return maxUnitsPerOrder;
    }

    public double restockPercentPerDay() {
        return restockPercentPerDay;
    }

    public double productionWeight() {
        return productionWeight;
    }

    public double volatilityPercent() {
        return volatilityPercent;
    }

    public double sentimentReversionPercent() {
        return sentimentReversionPercent;
    }

    public boolean shockEnabled() {
        return shockEnabled;
    }

    public int shockChancePercent() {
        return shockChancePercent;
    }

    public double shockMinPercent() {
        return shockMinPercent;
    }

    public double shockMaxPercent() {
        return shockMaxPercent;
    }

    public double shockDecayPercent() {
        return shockDecayPercent;
    }

    public long treasuryStart() {
        return treasuryStart;
    }

    public long treasuryFloor() {
        return treasuryFloor;
    }

    public long treasuryRefill() {
        return treasuryRefill;
    }

    public int historyPoints() {
        return historyPoints;
    }

    public boolean announceShocks() {
        return announceShocks;
    }

    public boolean dailyReport() {
        return dailyReport;
    }

    public String bankTitle() {
        return bankTitle;
    }

    public double reserveRatio() {
        return reserveRatioPercent / 100.0;
    }

    public double reserveRatioPercent() {
        return reserveRatioPercent;
    }

    public double depositMarginPercent() {
        return depositMarginPercent;
    }

    public double loanMarginPercent() {
        return loanMarginPercent;
    }

    public double termPremiumPerDayPercent() {
        return termPremiumPerDayPercent;
    }

    public int termMinDays() {
        return termMinDays;
    }

    public int termMaxDays() {
        return termMaxDays;
    }

    public double earlyWithdrawalPenaltyPercent() {
        return earlyWithdrawalPenaltyPercent;
    }

    public long centralBankFacility() {
        return centralBankFacility;
    }

    public long bailoutThreshold() {
        return bailoutThreshold;
    }

    public int loanMin() {
        return loanMin;
    }

    public int loanMaxDays() {
        return loanMaxDays;
    }

    public int loanDefaultDays() {
        return loanDefaultDays;
    }

    public double overdueExtraPercent() {
        return overdueExtraPercent;
    }

    public int overdueCreditDropPerDay() {
        return overdueCreditDropPerDay;
    }

    public double originationFeePercent() {
        return originationFeePercent;
    }

    public int maxLoans() {
        return maxLoans;
    }

    public int limitBase() {
        return limitBase;
    }

    public int limitPerLevel() {
        return limitPerLevel;
    }

    public double limitDepositPercent() {
        return limitDepositPercent;
    }

    public double dtiPercent() {
        return dtiPercent;
    }

    public int creditStart() {
        return creditStart;
    }

    public int creditOnRepay() {
        return creditOnRepay;
    }

    public int creditOnDefault() {
        return creditOnDefault;
    }

    public int creditRecoveryPerDay() {
        return creditRecoveryPerDay;
    }

    public double garnishPercent() {
        return garnishPercent;
    }

    public double inflationTargetPercent() {
        return inflationTargetPercent;
    }

    public double neutralRatePercent() {
        return neutralRatePercent;
    }

    public double taylorInflationWeight() {
        return taylorInflationWeight;
    }

    public double taylorOutputWeight() {
        return taylorOutputWeight;
    }

    public double rateFloorPercent() {
        return rateFloorPercent;
    }

    public double rateCeilingPercent() {
        return rateCeilingPercent;
    }

    public double rateMoveMaxPercent() {
        return rateMoveMaxPercent;
    }

    public boolean openMarketOperations() {
        return openMarketOperations;
    }

    public double omoBandPercent() {
        return omoBandPercent;
    }

    public int inflationMinDays() {
        return inflationMinDays;
    }

    public double inflationSmoothing() {
        return inflationSmoothing;
    }

    public double omoMaxSharePercent() {
        return omoMaxSharePercent;
    }

    public double gdpSmoothing() {
        return gdpSmoothingPercent / 100.0;
    }

    /** How much of excess money growth reaches prices rather than velocity. */
    public double moneyPassThrough() {
        return moneyPassThroughPercent / 100.0;
    }

    /** The most the price level may move in one economic day, as a fraction. */
    public double maxPriceDrift() {
        return maxPriceDriftPercent / 100.0;
    }

    public boolean broadcastPolicyChange() {
        return broadcastPolicyChange;
    }
}
