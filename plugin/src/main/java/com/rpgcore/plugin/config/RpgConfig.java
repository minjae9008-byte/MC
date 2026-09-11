package com.rpgcore.plugin.config;

import com.rpgcore.plugin.RpgCorePlugin;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Typed view over the three config files. Values are read once per reload and
 * cached in fields, so nothing on a hot path parses YAML.
 *
 *   settings.yml  the handful of knobs an operator actually tunes
 *   jobs.yml      job definitions, read by JobService
 *   config.yml    the long lists: item classes, anvil recipes, GUI, chat
 *
 * Both files get the jar's copy attached as defaults, so a config written by
 * an older build keeps working instead of reading back empty.
 */
public final class RpgConfig {

    public static final String SETTINGS_FILE = "settings.yml";
    public static final String JOBS_FILE = "jobs.yml";

    private final RpgCorePlugin plugin;
    private FileConfiguration settings;

    // --- settings.yml ---
    private int maxLevel;
    private int xpBase;
    private int xpGrowth;
    private double xpMultiplier;
    private int startingPoints;
    private int pointsPerLevel;
    private int xpPerMobKill;
    private int xpPerTreeLog;
    private boolean enchantRespectVanilla;
    private int enchantMaxLevel;
    private boolean jobsEnabled;
    private boolean partyEnabled;
    private boolean tradeEnabled;
    private boolean leaderboardEnabled;
    private boolean treeFellEnabled;
    private boolean durabilityScalingEnabled;
    private boolean anvilEnabled;
    private boolean proximityEnabled;
    private boolean hudEnabled;

    // --- config.yml ---
    private int baseHp;
    private int hpPerLevel;
    private int hpPerVit;
    private double attackPerStr;
    private double attackSpeedPerDex;
    private double speedPerAgi;
    private double jumpPerAgi;
    private double luckPerLuck;

    private int weightBase;
    private int weightPerStr;
    private int weightScanBatch;
    private int weightRescanInterval;

    private int treeFellMaxBlocks;
    private int treeFellPerTick;
    private int treeFellRadius;
    private boolean treeFellRespectProtection;
    private boolean treeFellDamageTool;
    private boolean treeFellSneakDisables;

    private int durabilityFullAbove;
    private int durabilityMinPerformance;
    private boolean durabilityAffectsAttack;
    private boolean durabilityAffectsArmor;
    private boolean durabilityAffectsMining;
    private boolean durabilityAffectsRanged;

    private int hudInterval;

    private double proximityRange;
    private boolean proximityHideOutOfRange;
    private String proximityFormat;

    private int leaderboardSize;
    private int leaderboardCacheSeconds;

    private int partyMaxSize;
    private int partyNameMaxLength;
    private int partyInviteSeconds;
    private boolean partyFriendlyFire;
    private String partyChatPrefix;
    private double partyXpShareRange;
    private double partyXpBonusPerMember;

    private int tradeRequestSeconds;
    private double tradeMaxDistance;

    public RpgConfig(RpgCorePlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        FileConfiguration c = plugin.getConfig();
        ConfigFiles.applyDefaults(plugin, c, "config.yml");

        boolean firstRun = !ConfigFiles.exists(plugin, SETTINGS_FILE);
        settings = ConfigFiles.load(plugin, SETTINGS_FILE);
        if (firstRun) {
            adoptLegacySettings(c);
        }

        maxLevel = Math.max(0, settings.getInt("level.max", 0));
        xpBase = Math.max(1, settings.getInt("level.xp-base", 100));
        xpGrowth = Math.max(0, settings.getInt("level.xp-growth", 50));
        xpMultiplier = Math.clamp(settings.getDouble("level.xp-multiplier", 1.0D), 1.0D, 10.0D);
        startingPoints = Math.max(0, settings.getInt("level.starting-points", 5));
        pointsPerLevel = Math.max(0, settings.getInt("level.points-per-level", 1));

        xpPerMobKill = Math.max(0, settings.getInt("xp-sources.per-mob-kill", 10));
        xpPerTreeLog = Math.max(0, settings.getInt("xp-sources.per-tree-log", 1));

        enchantRespectVanilla = settings.getBoolean("enchant.respect-vanilla-limits", false);
        enchantMaxLevel = Math.clamp(settings.getInt("enchant.max-level", 255), 1, 255);

        jobsEnabled = settings.getBoolean("features.jobs", true);
        partyEnabled = settings.getBoolean("features.parties", true);
        tradeEnabled = settings.getBoolean("features.trading", true);
        leaderboardEnabled = settings.getBoolean("features.leaderboard", true);
        treeFellEnabled = settings.getBoolean("features.tree-felling", true);
        durabilityScalingEnabled = settings.getBoolean("features.durability-scaling", true);
        anvilEnabled = settings.getBoolean("features.anvil-recipes", true);
        proximityEnabled = settings.getBoolean("features.proximity-chat", true);
        hudEnabled = settings.getBoolean("features.hud", true);

        baseHp = c.getInt("stats.base-hp", 20);
        hpPerLevel = c.getInt("stats.hp-per-level", 2);
        hpPerVit = c.getInt("stats.hp-per-vit", 1);
        attackPerStr = c.getDouble("stats.attack-damage-per-str", 0.5);
        attackSpeedPerDex = c.getDouble("stats.attack-speed-per-dex", 0.05);
        speedPerAgi = c.getDouble("stats.movement-speed-per-agi", 0.002);
        jumpPerAgi = c.getDouble("stats.jump-strength-per-agi", 0.01);
        luckPerLuck = c.getDouble("stats.luck-per-luck", 0.5);

        weightBase = c.getInt("weight.base-capacity", 100);
        weightPerStr = c.getInt("weight.capacity-per-str", 10);
        weightScanBatch = Math.max(1, c.getInt("weight.scans-per-tick", 8));
        weightRescanInterval = Math.max(20, c.getInt("weight.safety-rescan-ticks", 200));

        // Clamped: a zero here silently turns chain felling off, which looks
        // exactly like the feature being broken.
        treeFellMaxBlocks = Math.max(1, c.getInt("tree-felling.max-blocks", 256));
        treeFellPerTick = Math.max(1, c.getInt("tree-felling.blocks-per-tick", 12));
        treeFellRadius = Math.clamp(c.getInt("tree-felling.max-horizontal-radius", 5), 1, 32);
        treeFellRespectProtection = c.getBoolean("tree-felling.respect-protection-plugins", true);
        treeFellDamageTool = c.getBoolean("tree-felling.damage-tool", true);
        treeFellSneakDisables = c.getBoolean("tree-felling.sneak-disables", true);

        durabilityFullAbove = Math.clamp(c.getInt("durability-scaling.full-performance-above", 80), 1, 100);
        durabilityMinPerformance = Math.clamp(c.getInt("durability-scaling.minimum-performance", 50), 0, 100);
        durabilityAffectsAttack = c.getBoolean("durability-scaling.affects.attack-damage", true);
        durabilityAffectsArmor = c.getBoolean("durability-scaling.affects.armor", true);
        durabilityAffectsMining = c.getBoolean("durability-scaling.affects.mining-speed", true);
        durabilityAffectsRanged = c.getBoolean("durability-scaling.affects.ranged-damage", true);

        hudInterval = Math.max(5, c.getInt("hud.interval-ticks", 20));

        proximityRange = c.getDouble("proximity-chat.range", 24);
        proximityHideOutOfRange = c.getBoolean("proximity-chat.hide-out-of-range", true);
        proximityFormat = c.getString("proximity-chat.format", "&7[근접] &f%player%&7: &f%message%");

        leaderboardSize = Math.clamp(c.getInt("leaderboard.size", 10), 1, 50);
        leaderboardCacheSeconds = Math.max(0, c.getInt("leaderboard.cache-seconds", 30));

        partyMaxSize = Math.max(2, c.getInt("party.max-size", 5));
        partyNameMaxLength = Math.clamp(c.getInt("party.name-max-length", 16), 1, 32);
        partyInviteSeconds = Math.max(5, c.getInt("party.invite-timeout-seconds", 60));
        partyFriendlyFire = c.getBoolean("party.friendly-fire", false);
        partyChatPrefix = c.getString("party.chat-prefix", "&d[%party%] ");
        partyXpShareRange = Math.max(0, c.getDouble("party.xp.share-range", 50));
        partyXpBonusPerMember = Math.max(0, c.getDouble("party.xp.bonus-per-member", 0.05));

        tradeRequestSeconds = Math.max(5, c.getInt("trade.request-timeout-seconds", 60));
        tradeMaxDistance = Math.max(0, c.getDouble("trade.max-distance", 0));
    }

    /**
     * Carries values an operator had tuned in the old single-file layout into
     * the settings.yml being created for them, so upgrading does not quietly
     * reset the server's balance.
     */
    private void adoptLegacySettings(FileConfiguration legacy) {
        record Moved(String from, String to) {
        }
        Moved[] moves = {
                new Moved("leveling.xp-base", "level.xp-base"),
                new Moved("leveling.xp-growth", "level.xp-growth"),
                new Moved("leveling.starting-points", "level.starting-points"),
                new Moved("leveling.points-per-level", "level.points-per-level"),
                new Moved("xp-sources.per-mob-kill", "xp-sources.per-mob-kill"),
                new Moved("xp-sources.per-tree-log", "xp-sources.per-tree-log"),
                new Moved("tree-felling.enabled", "features.tree-felling"),
                new Moved("durability-scaling.enabled", "features.durability-scaling"),
                new Moved("anvil.enabled", "features.anvil-recipes"),
                new Moved("jobs.enabled", "features.jobs"),
                new Moved("party.enabled", "features.parties"),
                new Moved("trade.enabled", "features.trading"),
                new Moved("proximity-chat.enabled", "features.proximity-chat"),
                new Moved("hud.enabled", "features.hud"),
        };

        int carried = 0;
        for (Moved move : moves) {
            // isSet is false for keys only served by the bundled defaults, so
            // this copies what the operator actually wrote and nothing else.
            if (legacy.isSet(move.from())) {
                settings.set(move.to(), legacy.get(move.from()));
                carried++;
            }
        }
        if (carried > 0) {
            ConfigFiles.save(plugin, settings, SETTINGS_FILE);
            plugin.getLogger().info("Moved " + carried + " setting(s) from config.yml into "
                    + SETTINGS_FILE + ". The old keys in config.yml are now ignored and can be deleted.");
        }
    }

    /** The jobs.yml view, loaded fresh so /rpgcore reload picks up edits. */
    public FileConfiguration jobs() {
        return ConfigFiles.load(plugin, JOBS_FILE);
    }


    public int maxLevel() {
        return maxLevel;
    }

    public int xpBase() {
        return xpBase;
    }

    public int xpGrowth() {
        return xpGrowth;
    }

    public double xpMultiplier() {
        return xpMultiplier;
    }

    public int startingPoints() {
        return startingPoints;
    }

    public int pointsPerLevel() {
        return pointsPerLevel;
    }

    public int xpPerMobKill() {
        return xpPerMobKill;
    }

    public int xpPerTreeLog() {
        return xpPerTreeLog;
    }

    public boolean enchantRespectVanilla() {
        return enchantRespectVanilla;
    }

    public int enchantMaxLevel() {
        return enchantMaxLevel;
    }

    public boolean jobsEnabled() {
        return jobsEnabled;
    }

    public boolean partyEnabled() {
        return partyEnabled;
    }

    public boolean tradeEnabled() {
        return tradeEnabled;
    }

    public boolean leaderboardEnabled() {
        return leaderboardEnabled;
    }

    public boolean treeFellEnabled() {
        return treeFellEnabled;
    }

    public boolean durabilityScalingEnabled() {
        return durabilityScalingEnabled;
    }

    public boolean anvilEnabled() {
        return anvilEnabled;
    }

    public boolean proximityEnabled() {
        return proximityEnabled;
    }

    public boolean hudEnabled() {
        return hudEnabled;
    }

    public int baseHp() {
        return baseHp;
    }

    public int hpPerLevel() {
        return hpPerLevel;
    }

    public int hpPerVit() {
        return hpPerVit;
    }

    public double attackPerStr() {
        return attackPerStr;
    }

    public double attackSpeedPerDex() {
        return attackSpeedPerDex;
    }

    public double speedPerAgi() {
        return speedPerAgi;
    }

    public double jumpPerAgi() {
        return jumpPerAgi;
    }

    public double luckPerLuck() {
        return luckPerLuck;
    }

    public int weightBase() {
        return weightBase;
    }

    public int weightPerStr() {
        return weightPerStr;
    }

    public int weightScanBatch() {
        return weightScanBatch;
    }

    public int weightRescanInterval() {
        return weightRescanInterval;
    }

    public int treeFellMaxBlocks() {
        return treeFellMaxBlocks;
    }

    public int treeFellPerTick() {
        return treeFellPerTick;
    }

    public int treeFellRadius() {
        return treeFellRadius;
    }

    public boolean treeFellRespectProtection() {
        return treeFellRespectProtection;
    }

    public boolean treeFellDamageTool() {
        return treeFellDamageTool;
    }

    public boolean treeFellSneakDisables() {
        return treeFellSneakDisables;
    }

    public int durabilityFullAbove() {
        return durabilityFullAbove;
    }

    public int durabilityMinPerformance() {
        return durabilityMinPerformance;
    }

    public boolean durabilityAffectsAttack() {
        return durabilityAffectsAttack;
    }

    public boolean durabilityAffectsArmor() {
        return durabilityAffectsArmor;
    }

    public boolean durabilityAffectsMining() {
        return durabilityAffectsMining;
    }

    public boolean durabilityAffectsRanged() {
        return durabilityAffectsRanged;
    }

    public int hudInterval() {
        return hudInterval;
    }

    public double proximityRange() {
        return proximityRange;
    }

    public boolean proximityHideOutOfRange() {
        return proximityHideOutOfRange;
    }

    public String proximityFormat() {
        return proximityFormat;
    }

    public int leaderboardSize() {
        return leaderboardSize;
    }

    public int leaderboardCacheSeconds() {
        return leaderboardCacheSeconds;
    }

    public int partyMaxSize() {
        return partyMaxSize;
    }

    public int partyNameMaxLength() {
        return partyNameMaxLength;
    }

    public int partyInviteSeconds() {
        return partyInviteSeconds;
    }

    public boolean partyFriendlyFire() {
        return partyFriendlyFire;
    }

    public String partyChatPrefix() {
        return partyChatPrefix;
    }

    public double partyXpShareRange() {
        return partyXpShareRange;
    }

    public double partyXpBonusPerMember() {
        return partyXpBonusPerMember;
    }

    public int tradeRequestSeconds() {
        return tradeRequestSeconds;
    }

    public double tradeMaxDistance() {
        return tradeMaxDistance;
    }
}
