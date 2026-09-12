package com.rpgcore.plugin.data;

import com.rpgcore.plugin.progress.CounterType;
import com.rpgcore.plugin.stats.StatType;

import java.util.EnumMap;
import java.util.Map;

/**
 * In-memory cache of one player's RPG state. Everything the HUD and the weight
 * loop read every few ticks lives here, so the hot paths never touch the
 * scoreboard; {@link PlayerDataManager} writes changed values through to the
 * vanilla scoreboard (which is also what persists them across restarts).
 */
public final class PlayerData {

    private int level = 1;
    private int xp;
    private int xpNeed;
    private int points;
    /** Job id, or null when the player has not picked one. */
    private String jobId;
    private final Map<StatType, Integer> stats = new EnumMap<>(StatType.class);
    /** Spendable currency: achievement rewards in, duel wagers out. */
    private int gold;
    /** Lifetime tallies the achievements are written against. */
    private final Map<CounterType, Integer> counters = new EnumMap<>(CounterType.class);

    private int weight;
    private int weightMax;
    private int weightTier;
    private int maxHealth;

    /** Set when something changed and needs writing back to the scoreboard. */
    private boolean dirty;
    /** Set when the inventory changed and the weight must be recomputed. */
    private boolean weightDirty = true;
    /**
     * False until the encumbrance tier's attribute modifiers have been applied
     * at least once this session. Attribute modifiers persist in the player's
     * saved data, so a player who logs out encumbered comes back still carrying
     * them; without this the first recompute would see tier 0 == tier 0 and
     * leave the stale penalty in place forever.
     */
    private boolean tierApplied;
    /** Set when the equipped gear, or its remaining durability, changed. */
    private boolean gearDirty = true;

    /**
     * Remaining durability of the held weapon and of the worn armour, as
     * percentages. Derived values: they are recomputed from the inventory, so
     * unlike the fields above they are never mirrored to the scoreboard.
     */
    private int weaponCondition = 100;
    private int armorCondition = 100;
    private boolean gearWarned;
    private long lastGearWarnMs;
    private long lastMaxLevelNoticeMs;

    public PlayerData() {
        for (StatType type : StatType.values()) {
            stats.put(type, 0);
        }
        for (CounterType type : CounterType.values()) {
            counters.put(type, 0);
        }
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

    public String jobId() {
        return jobId;
    }

    public void jobId(String jobId) {
        this.jobId = jobId;
    }

    public int stat(StatType type) {
        return stats.getOrDefault(type, 0);
    }

    public void stat(StatType type, int value) {
        stats.put(type, value);
        this.dirty = true;
    }

    public int gold() {
        return gold;
    }

    public void gold(int gold) {
        this.gold = Math.max(0, gold);
        this.dirty = true;
    }

    /**
     * LEVEL is derived rather than counted, so an achievement written against
     * it reads the same way as one written against a real tally.
     */
    public int counter(CounterType type) {
        return type == CounterType.LEVEL ? level : counters.getOrDefault(type, 0);
    }

    public void counter(CounterType type, int value) {
        if (type == CounterType.LEVEL) {
            return;
        }
        counters.put(type, Math.max(0, value));
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

    public void clearWeightDirty() {
        this.weightDirty = false;
    }

    public boolean tierApplied() {
        return tierApplied;
    }

    public void markTierApplied() {
        this.tierApplied = true;
    }

    public boolean gearDirty() {
        return gearDirty;
    }

    public void markGearDirty() {
        this.gearDirty = true;
    }

    public void clearGearDirty() {
        this.gearDirty = false;
    }

    /** Marks everything an inventory change can affect. */
    public void markInventoryDirty() {
        this.weightDirty = true;
        this.gearDirty = true;
    }

    public int weaponCondition() {
        return weaponCondition;
    }

    public void weaponCondition(int weaponCondition) {
        this.weaponCondition = weaponCondition;
    }

    public int armorCondition() {
        return armorCondition;
    }

    public void armorCondition(int armorCondition) {
        this.armorCondition = armorCondition;
    }

    public boolean gearWarned() {
        return gearWarned;
    }

    public void gearWarned(boolean gearWarned) {
        this.gearWarned = gearWarned;
    }

    public long lastGearWarnMs() {
        return lastGearWarnMs;
    }

    public long lastMaxLevelNoticeMs() {
        return lastMaxLevelNoticeMs;
    }

    public void lastMaxLevelNoticeMs(long lastMaxLevelNoticeMs) {
        this.lastMaxLevelNoticeMs = lastMaxLevelNoticeMs;
    }

    public void lastGearWarnMs(long lastGearWarnMs) {
        this.lastGearWarnMs = lastGearWarnMs;
    }

    /** Load percentage (weight/weightMax * 100), guarded against a zero max. */
    public int loadPercent() {
        if (weightMax <= 0) {
            return 0;
        }
        return (int) ((long) weight * 100L / weightMax);
    }
}
