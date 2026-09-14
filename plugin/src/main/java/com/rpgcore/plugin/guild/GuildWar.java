package com.rpgcore.plugin.guild;

import java.util.UUID;

/**
 * One war between two guilds.
 *
 * A war has three phases and they exist for different reasons.
 *
 * Declared, but not yet joined: the preparation window. Without it, declaring
 * war is the same action as winning it - you rush the enemy flag while nobody
 * on their side is online, and the mechanic reads as griefing with extra
 * steps. The window is the defenders' notice.
 *
 * Joined: both guilds' claims are open to the other, in both directions.
 * A war is symmetric on purpose. Declaring puts your own flag at risk too,
 * which is what turns "declare on the weakest guild" into a real decision
 * rather than free money.
 *
 * Over: settled by a banner coming down, or by the clock running out with both
 * flags still standing, which is a draw and moves no gold.
 */
public final class GuildWar {

    /** Where a war is in its life. */
    public enum Phase {
        PREPARING, FIGHTING, OVER
    }

    private final UUID attacker;
    private final UUID defender;
    private final long declaredAtMs;
    private final long fightingFromMs;
    private final long endsAtMs;
    /** Set the moment a result is decided, so it can only be settled once. */
    private boolean over;

    GuildWar(UUID attacker, UUID defender, long declaredAtMs, long fightingFromMs, long endsAtMs) {
        this.attacker = attacker;
        this.defender = defender;
        this.declaredAtMs = declaredAtMs;
        this.fightingFromMs = fightingFromMs;
        this.endsAtMs = endsAtMs;
    }

    public UUID attacker() {
        return attacker;
    }

    public UUID defender() {
        return defender;
    }

    public long declaredAtMs() {
        return declaredAtMs;
    }

    public long fightingFromMs() {
        return fightingFromMs;
    }

    public long endsAtMs() {
        return endsAtMs;
    }

    public boolean involves(UUID guildId) {
        return attacker.equals(guildId) || defender.equals(guildId);
    }

    public boolean isBetween(UUID a, UUID b) {
        return (attacker.equals(a) && defender.equals(b))
                || (attacker.equals(b) && defender.equals(a));
    }

    public UUID opponentOf(UUID guildId) {
        return attacker.equals(guildId) ? defender : attacker;
    }

    public Phase phase(long nowMs) {
        if (over || nowMs >= endsAtMs) {
            return Phase.OVER;
        }
        return nowMs < fightingFromMs ? Phase.PREPARING : Phase.FIGHTING;
    }

    /** True only while blows actually land: banners are safe until then. */
    public boolean fighting(long nowMs) {
        return phase(nowMs) == Phase.FIGHTING;
    }

    public boolean over() {
        return over;
    }

    /** One-way. Returns false when this war has already been settled. */
    boolean settle() {
        if (over) {
            return false;
        }
        over = true;
        return true;
    }

    /** "3분 12초" until the fighting starts, or until the war expires. */
    public String remaining(long nowMs) {
        long target = phase(nowMs) == Phase.PREPARING ? fightingFromMs : endsAtMs;
        long seconds = Math.max(0L, (target - nowMs) / 1000L);
        long minutes = seconds / 60L;
        if (minutes >= 60L) {
            return (minutes / 60L) + "시간 " + (minutes % 60L) + "분";
        }
        return minutes > 0L ? minutes + "분 " + (seconds % 60L) + "초" : seconds + "초";
    }
}
