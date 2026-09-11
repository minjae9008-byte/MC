package com.rpgcore.plugin.party;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * One party. Parties live in memory only: they are a session-scoped grouping,
 * and a server restart is a natural point for them to end.
 */
public final class Party {

    private UUID leader;
    private final Set<UUID> members = new LinkedHashSet<>();

    Party(UUID leader) {
        this.leader = leader;
        this.members.add(leader);
    }

    public UUID leader() {
        return leader;
    }

    void leader(UUID leader) {
        this.leader = leader;
    }

    public Set<UUID> members() {
        return Set.copyOf(members);
    }

    public int size() {
        return members.size();
    }

    public boolean contains(UUID uuid) {
        return members.contains(uuid);
    }

    public boolean isLeader(UUID uuid) {
        return leader.equals(uuid);
    }

    void add(UUID uuid) {
        members.add(uuid);
    }

    void remove(UUID uuid) {
        members.remove(uuid);
    }

    /** Ordered so /party list always reads the same way. */
    Set<UUID> ordered() {
        return members;
    }
}
