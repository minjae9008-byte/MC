package com.rpgcore.plugin.duel;

import java.util.UUID;

/**
 * One duel in progress: who is fighting, what each of them staked, and when it
 * started so it can be called off if nobody lands a hit.
 */
public final class DuelSession {

    private final UUID first;
    private final UUID second;
    private final DuelStake firstStake;
    private final DuelStake secondStake;
    private long startedAtMs;
    /** Set the moment a result is decided, so a second hit cannot settle it twice. */
    private boolean finished;
    /**
     * True until the countdown runs out. Blows between the two are refused
     * while it holds, so accepting cannot hand the challenger a free hit on
     * someone still reading the message.
     */
    private boolean pending;

    DuelSession(UUID first, UUID second, DuelStake firstStake, DuelStake secondStake, boolean pending) {
        this.first = first;
        this.second = second;
        this.firstStake = firstStake;
        this.secondStake = secondStake;
        this.startedAtMs = System.currentTimeMillis();
        this.pending = pending;
    }

    public UUID first() {
        return first;
    }

    public UUID second() {
        return second;
    }

    public boolean contains(UUID uuid) {
        return first.equals(uuid) || second.equals(uuid);
    }

    public UUID opponentOf(UUID uuid) {
        return first.equals(uuid) ? second : first;
    }

    public DuelStake stakeOf(UUID uuid) {
        return first.equals(uuid) ? firstStake : secondStake;
    }

    public long ageSeconds() {
        return (System.currentTimeMillis() - startedAtMs) / 1000L;
    }

    public boolean finished() {
        return finished;
    }

    /** True while the countdown is still running. */
    public boolean pending() {
        return pending;
    }

    /** The countdown is over: the clock starts here, not at the accept. */
    void begin() {
        this.pending = false;
        this.startedAtMs = System.currentTimeMillis();
    }

    void finish() {
        this.finished = true;
    }
}
