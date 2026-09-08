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

    private double proximityRange;
    private boolean proximityEnabled;
    private boolean proximityHideOutOfRange;
    private String proximityFormat;

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

        proximityEnabled = c.getBoolean("proximity-chat.enabled", true);
        proximityRange = c.getDouble("proximity-chat.range", 24);
        proximityHideOutOfRange = c.getBoolean("proximity-chat.hide-out-of-range", true);
        proximityFormat = c.getString("proximity-chat.format", "&7[근접] &f%player%&7: &f%message%");
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
}
