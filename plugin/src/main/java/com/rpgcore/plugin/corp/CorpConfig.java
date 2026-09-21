package com.rpgcore.plugin.corp;

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
 * Typed view over corporations.yml - factories, share rules, and the
 * companies the server starts with.
 *
 * Read once per reload into fields, like every other config here: the daily
 * corporate pass touches these for every factory of every company.
 */
public final class CorpConfig {

    public static final String FILE = "corporations.yml";

    /**
     * One kind of factory.
     *
     * {@code inputs} is what makes a supply chain rather than a list of money
     * printers: a smelter needs iron from somewhere, so either the company
     * digs its own or it buys on the market, and either way somebody has to
     * have produced it.
     */
    public record FactoryType(String id, String name, Material icon, Material output,
                              double perDay, long buildCost, long upkeep,
                              Map<Material, Double> inputs) {
    }

    /** A company the server seeds so the market is not empty on day one. */
    public record Seed(String name, String ticker, long cash, long shares,
                       Map<String, Integer> factories) {
    }

    private final RpgCorePlugin plugin;

    private int nameMaxLength;
    private int maxEmployees;
    private long createCost;
    private long createCostToTreasury;
    private int inviteTimeoutSeconds;
    private long wagePerEmployee;
    private double ceoWageMultiplier;
    private int unpaidDaysBeforeQuit;
    private int defaultSellPercent;
    private boolean defaultAutoBuyInputs;
    private long warehouseCap;
    private long bankruptcyDebtLimit;

    private long founderShares;
    private double bookWeight;
    private double earningsMultiple;
    private double tradeImpactPercent;
    private double shareReversionPercent;
    private double minSharePrice;
    private double shareSpreadPercent;
    private double shareTaxPercent;
    private double buybackCashPercent;
    private boolean npcAutoIssue;
    private int defaultDividendPercent;
    private long minDividendPerShare;
    private double minTakeoverPremium;
    private double maxTakeoverPremium;
    private int offerTimeoutSeconds;

    private double upgradeOutput;
    private double upgradeCost;
    private double upgradeUpkeep;
    private int maxLevel;

    private boolean seedEnabled;

    private final Map<String, FactoryType> factories = new LinkedHashMap<>();
    private final List<Seed> seeds = new ArrayList<>();

    public CorpConfig(RpgCorePlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        FileConfiguration c = ConfigFiles.load(plugin, FILE);

        nameMaxLength = Math.clamp(c.getInt("company.name-max-length", 18), 3, 32);
        maxEmployees = Math.clamp(c.getInt("company.max-employees", 20), 1, 200);
        createCost = Math.max(0, c.getLong("company.create-cost", 25_000));
        createCostToTreasury = Math.clamp(c.getLong("company.create-cost-to-treasury", 5_000), 0, createCost);
        inviteTimeoutSeconds = Math.clamp(c.getInt("company.invite-timeout-seconds", 60), 5, 3600);
        wagePerEmployee = Math.max(0, c.getLong("company.wage-per-employee", 300));
        ceoWageMultiplier = Math.clamp(c.getDouble("company.ceo-wage-multiplier", 2.0), 0.0, 20.0);
        unpaidDaysBeforeQuit = Math.clamp(c.getInt("company.unpaid-days-before-quit", 3), 1, 60);
        defaultSellPercent = Math.clamp(c.getInt("company.default-sell-percent", 100), 0, 100);
        defaultAutoBuyInputs = c.getBoolean("company.default-auto-buy-inputs", true);
        warehouseCap = Math.max(1, c.getLong("company.warehouse-cap-per-item", 200_000));
        bankruptcyDebtLimit = Math.min(0, c.getLong("company.bankruptcy-debt-limit", -50_000));

        founderShares = Math.clamp(c.getLong("shares.founder-shares", 10_000), 100, 100_000_000L);
        bookWeight = Math.clamp(c.getDouble("shares.book-weight-percent", 50), 0, 100) / 100.0;
        earningsMultiple = Math.clamp(c.getDouble("shares.earnings-multiple", 8), 0.0, 100.0);
        tradeImpactPercent = Math.clamp(c.getDouble("shares.trade-impact-percent", 12), 0.0, 100.0);
        shareReversionPercent = Math.clamp(c.getDouble("shares.sentiment-reversion-percent", 25), 0.0, 100.0);
        minSharePrice = Math.max(1, c.getDouble("shares.min-price", 1));
        // Never zero, for the same reason the goods market's spread is never
        // zero: buying and selling at one price is a free money loop.
        shareSpreadPercent = Math.clamp(c.getDouble("shares.spread-percent", 4), 1.0, 50.0);
        shareTaxPercent = Math.clamp(c.getDouble("shares.trade-tax-percent", 2), 0.0, 50.0);
        buybackCashPercent = Math.clamp(c.getDouble("shares.buyback-cash-percent-per-day", 25), 0.0, 100.0);
        npcAutoIssue = c.getBoolean("shares.npc-auto-issue", true);
        defaultDividendPercent = Math.clamp(c.getInt("shares.default-dividend-percent", 40), 0, 100);
        minDividendPerShare = Math.max(0, c.getLong("shares.min-dividend-per-share", 1));
        minTakeoverPremium = Math.clamp(c.getDouble("shares.min-takeover-premium-percent", 10), 0.0, 500.0);
        maxTakeoverPremium = Math.max(minTakeoverPremium,
                c.getDouble("shares.max-takeover-premium-percent", 200));
        offerTimeoutSeconds = Math.clamp(c.getInt("shares.offer-timeout-seconds", 300), 30, 86_400);

        upgradeOutput = Math.max(1.0, c.getDouble("upgrade.output-multiplier", 1.6));
        upgradeCost = Math.max(1.0, c.getDouble("upgrade.cost-multiplier", 1.8));
        upgradeUpkeep = Math.max(1.0, c.getDouble("upgrade.upkeep-multiplier", 1.45));
        maxLevel = Math.clamp(c.getInt("upgrade.max-level", 5), 1, 20);

        seedEnabled = c.getBoolean("seed.enabled", true);

        loadFactories(c);
        loadSeeds(c);
    }

    private void loadFactories(FileConfiguration c) {
        factories.clear();
        ConfigurationSection root = c.getConfigurationSection("factories");
        if (root == null) {
            plugin.getLogger().warning(FILE + ": no factories section - companies will have nothing to build.");
            return;
        }
        for (String id : root.getKeys(false)) {
            ConfigurationSection node = root.getConfigurationSection(id);
            if (node == null) {
                continue;
            }
            Material output = Material.matchMaterial(node.getString("output", ""));
            if (output == null || output.isAir()) {
                plugin.getLogger().warning(FILE + ": factory '" + id + "' has no valid output - skipped.");
                continue;
            }
            Material icon = Material.matchMaterial(node.getString("icon", ""));
            Map<Material, Double> inputs = new LinkedHashMap<>();
            ConfigurationSection inputSection = node.getConfigurationSection("inputs");
            if (inputSection != null) {
                for (String key : inputSection.getKeys(false)) {
                    Material material = Material.matchMaterial(key);
                    double amount = inputSection.getDouble(key, 0);
                    if (material == null || amount <= 0) {
                        plugin.getLogger().warning(FILE + ": factory '" + id + "' input '" + key
                                + "' is unknown or zero - ignored.");
                        continue;
                    }
                    inputs.put(material, amount);
                }
            }
            factories.put(id.toLowerCase(Locale.ROOT), new FactoryType(
                    id.toLowerCase(Locale.ROOT),
                    node.getString("name", id),
                    icon == null ? output : icon,
                    output,
                    Math.max(0.0001, node.getDouble("per-day", 1)),
                    Math.max(0, node.getLong("build", 10_000)),
                    Math.max(0, node.getLong("upkeep", 0)),
                    Map.copyOf(inputs)));
        }
        plugin.getLogger().info("Factory types loaded: " + factories.size() + ".");
    }

    private void loadSeeds(FileConfiguration c) {
        seeds.clear();
        for (Map<?, ?> row : c.getMapList("seed.companies")) {
            Object name = row.get("name");
            if (name == null) {
                continue;
            }
            Map<String, Integer> plants = new LinkedHashMap<>();
            if (row.get("factories") instanceof Map<?, ?> raw) {
                for (Map.Entry<?, ?> entry : raw.entrySet()) {
                    int count = entry.getValue() instanceof Number n ? n.intValue() : 0;
                    if (count > 0) {
                        plants.put(String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT), count);
                    }
                }
            }
            seeds.add(new Seed(
                    String.valueOf(name),
                    row.get("ticker") == null ? "----"
                            : String.valueOf(row.get("ticker")).toUpperCase(Locale.ROOT),
                    row.get("cash") instanceof Number n ? n.longValue() : 100_000,
                    row.get("shares") instanceof Number n ? n.longValue() : 10_000,
                    plants));
        }
    }

    public FactoryType factory(String id) {
        return id == null ? null : factories.get(id.toLowerCase(Locale.ROOT));
    }

    public List<FactoryType> factories() {
        return List.copyOf(factories.values());
    }

    public List<Seed> seeds() {
        return List.copyOf(seeds);
    }

    /** Output per day of a factory at a given level. */
    public double outputAt(FactoryType type, int level) {
        return type.perDay() * Math.pow(upgradeOutput, Math.max(0, level - 1));
    }

    /** What the NEXT level costs to build, from the current one. */
    public long upgradeCostAt(FactoryType type, int level) {
        return Math.round(type.buildCost() * Math.pow(upgradeCost, Math.max(0, level)));
    }

    public long upkeepAt(FactoryType type, int level) {
        return Math.round(type.upkeep() * Math.pow(upgradeUpkeep, Math.max(0, level - 1)));
    }

    public int nameMaxLength() {
        return nameMaxLength;
    }

    public int maxEmployees() {
        return maxEmployees;
    }

    public long createCost() {
        return createCost;
    }

    public long createCostToTreasury() {
        return createCostToTreasury;
    }

    public int inviteTimeoutSeconds() {
        return inviteTimeoutSeconds;
    }

    public long wagePerEmployee() {
        return wagePerEmployee;
    }

    public double ceoWageMultiplier() {
        return ceoWageMultiplier;
    }

    public int unpaidDaysBeforeQuit() {
        return unpaidDaysBeforeQuit;
    }

    public int defaultSellPercent() {
        return defaultSellPercent;
    }

    public boolean defaultAutoBuyInputs() {
        return defaultAutoBuyInputs;
    }

    public long warehouseCap() {
        return warehouseCap;
    }

    public long bankruptcyDebtLimit() {
        return bankruptcyDebtLimit;
    }

    public long founderShares() {
        return founderShares;
    }

    public double bookWeight() {
        return bookWeight;
    }

    public double earningsMultiple() {
        return earningsMultiple;
    }

    public double tradeImpactPercent() {
        return tradeImpactPercent;
    }

    public double shareReversionPercent() {
        return shareReversionPercent;
    }

    public double minSharePrice() {
        return minSharePrice;
    }

    public double shareSpreadPercent() {
        return shareSpreadPercent;
    }

    public double shareTaxPercent() {
        return shareTaxPercent;
    }

    public double buybackCashPercent() {
        return buybackCashPercent;
    }

    public boolean npcAutoIssue() {
        return npcAutoIssue;
    }

    public int defaultDividendPercent() {
        return defaultDividendPercent;
    }

    public long minDividendPerShare() {
        return minDividendPerShare;
    }

    public double minTakeoverPremium() {
        return minTakeoverPremium;
    }

    public double maxTakeoverPremium() {
        return maxTakeoverPremium;
    }

    public int offerTimeoutSeconds() {
        return offerTimeoutSeconds;
    }

    public int maxLevel() {
        return maxLevel;
    }

    public boolean seedEnabled() {
        return seedEnabled;
    }
}
