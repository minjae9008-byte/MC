package com.rpgcore.plugin.config;

import com.rpgcore.plugin.RpgCorePlugin;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Typed view over config.yml. Values are read once per reload and cached in
 * fields, so nothing on a hot path (weight loop, HUD, tree-fell) parses YAML.
 */
public final class RpgConfig {

    private final RpgCorePlugin plugin;

    private int xpBase;
    private int xpGrowth;
    private int startingPoints;
    private int pointsPerLevel;

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

    private boolean treeFellEnabled;
    private int treeFellMaxBlocks;
    private int treeFellPerTick;
    private boolean treeFellRespectProtection;
    private boolean treeFellDamageTool;
    private boolean treeFellSneakDisables;

    private int hudInterval;
    private boolean hudEnabled;

    private int xpPerMobKill;
    private int xpPerTreeLog;

    private boolean durabilityScalingEnabled;
    private int durabilityFullAbove;
    private int durabilityMinPerformance;
    private boolean durabilityAffectsAttack;
    private boolean durabilityAffectsArmor;
    private boolean durabilityAffectsMining;
    private boolean durabilityAffectsRanged;

    private boolean anvilEnabled;

    private double proximityRange;
    private boolean proximityEnabled;
    private boolean proximityHideOutOfRange;
    private String proximityFormat;

    private int leaderboardSize;
    private int leaderboardCacheSeconds;

    private boolean partyEnabled;
    private int partyMaxSize;
    private int partyInviteSeconds;
    private boolean partyFriendlyFire;
    private String partyChatPrefix;
    private double partyXpShareRange;
    private double partyXpBonusPerMember;

    public RpgConfig(RpgCorePlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        plugin.reloadConfig();
        FileConfiguration c = plugin.getConfig();

        xpBase = c.getInt("leveling.xp-base", 100);
        xpGrowth = c.getInt("leveling.xp-growth", 50);
        startingPoints = c.getInt("leveling.starting-points", 5);
        pointsPerLevel = c.getInt("leveling.points-per-level", 1);

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
        weightScanBatch = c.getInt("weight.scans-per-tick", 8);
        weightRescanInterval = c.getInt("weight.safety-rescan-ticks", 200);

        treeFellEnabled = c.getBoolean("tree-felling.enabled", true);
        treeFellMaxBlocks = c.getInt("tree-felling.max-blocks", 256);
        treeFellPerTick = c.getInt("tree-felling.blocks-per-tick", 12);
        treeFellRespectProtection = c.getBoolean("tree-felling.respect-protection-plugins", true);
        treeFellDamageTool = c.getBoolean("tree-felling.damage-tool", true);
        treeFellSneakDisables = c.getBoolean("tree-felling.sneak-disables", true);

        hudEnabled = c.getBoolean("hud.enabled", true);
        hudInterval = Math.max(5, c.getInt("hud.interval-ticks", 20));

        xpPerMobKill = c.getInt("xp-sources.per-mob-kill", 10);
        xpPerTreeLog = c.getInt("xp-sources.per-tree-log", 1);

        durabilityScalingEnabled = c.getBoolean("durability-scaling.enabled", true);
        // Clamped so a mistyped curve cannot invert (a floor above the
        // threshold would mean worn gear outperforms pristine gear).
        durabilityFullAbove = Math.clamp(c.getInt("durability-scaling.full-performance-above", 50), 1, 100);
        durabilityMinPerformance = Math.clamp(c.getInt("durability-scaling.minimum-performance", 50), 0, 100);
        durabilityAffectsAttack = c.getBoolean("durability-scaling.affects.attack-damage", true);
        durabilityAffectsArmor = c.getBoolean("durability-scaling.affects.armor", true);
        durabilityAffectsMining = c.getBoolean("durability-scaling.affects.mining-speed", true);
        durabilityAffectsRanged = c.getBoolean("durability-scaling.affects.ranged-damage", true);

        anvilEnabled = c.getBoolean("anvil.enabled", true);

        proximityEnabled = c.getBoolean("proximity-chat.enabled", true);
        proximityRange = c.getDouble("proximity-chat.range", 24);
        proximityHideOutOfRange = c.getBoolean("proximity-chat.hide-out-of-range", true);
        proximityFormat = c.getString("proximity-chat.format", "&7[근접] &f%player%&7: &f%message%");

        leaderboardSize = Math.clamp(c.getInt("leaderboard.size", 10), 1, 50);
        leaderboardCacheSeconds = Math.max(0, c.getInt("leaderboard.cache-seconds", 30));

        partyEnabled = c.getBoolean("party.enabled", true);
        partyMaxSize = Math.max(2, c.getInt("party.max-size", 5));
        partyInviteSeconds = Math.max(5, c.getInt("party.invite-timeout-seconds", 60));
        partyFriendlyFire = c.getBoolean("party.friendly-fire", false);
        partyChatPrefix = c.getString("party.chat-prefix", "&d[파티] ");
        partyXpShareRange = Math.max(0, c.getDouble("party.xp.share-range", 50));
        partyXpBonusPerMember = Math.max(0, c.getDouble("party.xp.bonus-per-member", 0.05));
    }

    public int xpBase() {
        return xpBase;
    }

    public int xpGrowth() {
        return xpGrowth;
    }

    public int startingPoints() {
        return startingPoints;
    }

    public int pointsPerLevel() {
        return pointsPerLevel;
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

    public boolean treeFellEnabled() {
        return treeFellEnabled;
    }

    public int treeFellMaxBlocks() {
        return treeFellMaxBlocks;
    }

    public int treeFellPerTick() {
        return treeFellPerTick;
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

    public boolean hudEnabled() {
        return hudEnabled;
    }

    public int hudInterval() {
        return hudInterval;
    }

    public int xpPerMobKill() {
        return xpPerMobKill;
    }

    public int xpPerTreeLog() {
        return xpPerTreeLog;
    }

    public boolean durabilityScalingEnabled() {
        return durabilityScalingEnabled;
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

    public boolean anvilEnabled() {
        return anvilEnabled;
    }

    public boolean proximityEnabled() {
        return proximityEnabled;
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

    public boolean partyEnabled() {
        return partyEnabled;
    }

    public int partyMaxSize() {
        return partyMaxSize;
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
}
