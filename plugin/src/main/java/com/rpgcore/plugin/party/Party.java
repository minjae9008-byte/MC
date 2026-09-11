package com.rpgcore.plugin.party;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One party. Parties outlive a restart, so a party carries its own id and
 * remembers each member's last known name - that is what lets /party list show
 * who is in it while they are offline.
 */
public final class Party {

    private final UUID id;
    private String name;
    private UUID leader;
    /** Member id -> last known name, in join order. */
    private final Map<UUID, String> members = new LinkedHashMap<>();

    Party(UUID id, String name, UUID leader, String leaderName) {
        this.id = id;
        this.name = name;
        this.leader = leader;
        this.members.put(leader, leaderName);
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    void name(String name) {
        this.name = name;
    }

    public UUID leader() {
        return leader;
    }

    void leader(UUID leader) {
        this.leader = leader;
    }

    public Set<UUID> members() {
        return Set.copyOf(members.keySet());
    }

    /** Join order, so /party list always reads the same way. */
    Set<UUID> ordered() {
        return members.keySet();
    }

    public String nameOf(UUID uuid) {
        return members.get(uuid);
    }

    public int size() {
        return members.size();
    }

    public boolean contains(UUID uuid) {
        return members.containsKey(uuid);
    }

    public boolean isLeader(UUID uuid) {
        return leader.equals(uuid);
    }

    void add(UUID uuid, String memberName) {
        members.put(uuid, memberName);
    }

    void remove(UUID uuid) {
        members.remove(uuid);
    }

    /** Keeps the stored name current when a player logs in under a new one. */
    void refreshName(UUID uuid, String memberName) {
        if (members.containsKey(uuid)) {
            members.put(uuid, memberName);
        }
    }
}
