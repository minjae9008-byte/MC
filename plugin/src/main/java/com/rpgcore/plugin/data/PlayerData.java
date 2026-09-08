package com.rpgcore.plugin.data;

import com.rpgcore.plugin.stats.StatType;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * In-memory cache of one player's RPG state. Everything the HUD and the weight
 * loop read every few ticks lives here, so the hot paths never touch the
 * scoreboard; {@link PlayerDataManager} writes changed values through to the
 * vanilla scoreboard (which is also what persists them across restarts).
 */
public final class PlayerData {

    private final UUID uuid;

    private int level = 1;
    private int xp;
    private int xpNeed;
    private int points;
    private final Map<StatType, Integer> stats = new EnumMap<>(StatType.class);

    private int weight;
    private int weightMax;
    private int weightTier;
    private int maxHealth;

    /** Set when something changed and needs writing back to the scoreboard. */
    private boolean dirty;
    /** Set when the inventory changed and the weight must be recomputed. */
    private boolean weightDirty = true;

    public PlayerData(UUID uuid) {
        this.uuid = uuid;
        for (StatType type : StatType.values()) {
            stats.put(type, 0);
        }
    }

    public UUID uuid() {
        return uuid;
    }

    public int level() {
        return level;
    }

    public void level(int level) {
        this.level = level;
        this.dirty = true;
    }

    public int xp() {
        return xp;
    }

    public void xp(int xp) {
        this.xp = xp;
        this.dirty = true;
    }

    public int xpNeed() {
        return xpNeed;
    }

    public void xpNeed(int xpNeed) {
        this.xpNeed = xpNeed;
        this.dirty = true;
    }

    public int points() {
        return points;
    }

    public void points(int points) {
        this.points = points;
        this.dirty = true;
    }

    public int stat(StatType type) {
        return stats.getOrDefault(type, 0);
    }

    public void stat(StatType type, int value) {
        stats.put(type, value);
        this.dirty = true;
    }

    public int weight() {
        return weight;
    }

    public void weight(int weight) {
        this.weight = weight;
        this.dirty = true;
    }

    public int weightMax() {
        return weightMax;
    }

    public void weightMax(int weightMax) {
        this.weightMax = weightMax;
        this.dirty = true;
    }

    public int weightTier() {
        return weightTier;
    }

    public void weightTier(int weightTier) {
        this.weightTier = weightTier;
        this.dirty = true;
    }

    public int maxHealth() {
        return maxHealth;
    }

    public void maxHealth(int maxHealth) {
        this.maxHealth = maxHealth;
        this.dirty = true;
    }

    public boolean dirty() {
        return dirty;
    }

    public void markDirty() {
        this.dirty = true;
    }

    public void clearDirty() {
        this.dirty = false;
    }

    public boolean weightDirty() {
        return weightDirty;
    }

    public void markWeightDirty() {
        this.weightDirty = true;
    }

    public void clearWeightDirty() {
        this.weightDirty = false;
    }

    /** Load percentage (weight/weightMax * 100), guarded against a zero max. */
    public int loadPercent() {
        if (weightMax <= 0) {
            return 0;
        }
        return (int) ((long) weight * 100L / weightMax);
    }
}
