package com.rpgcore.plugin.blueprint;

import java.util.UUID;

/**
 * A building going up.
 *
 * All the progress there is is one cursor into the blueprint's block array.
 * That is deliberate: the cursor survives a restart in a single integer, and
 * because the array is ordered bottom-up, resuming from it puts the next
 * block exactly where the last one left off rather than somewhere plausible.
 */
public final class ConstructionSite {

    private final UUID id;
    private final UUID blueprintId;
    private final String blueprintName;
    private final UUID world;
    private final int originX;
    private final int originY;
    private final int originZ;
    private final UUID owner;
    private final String ownerName;
    /** The company that paid, or null when a player paid out of pocket. */
    private final UUID company;
    private final long paid;
    private final long startedAt;

    private int cursor;
    private int placed;
    private final int solidTotal;

    public ConstructionSite(UUID id, UUID blueprintId, String blueprintName, UUID world,
                            int originX, int originY, int originZ, UUID owner, String ownerName,
                            UUID company, long paid, long startedAt, int cursor, int placed,
                            int solidTotal) {
        this.id = id;
        this.blueprintId = blueprintId;
        this.blueprintName = blueprintName;
        this.world = world;
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.owner = owner;
        this.ownerName = ownerName;
        this.company = company;
        this.paid = paid;
        this.startedAt = startedAt;
        this.cursor = cursor;
        this.placed = placed;
        this.solidTotal = solidTotal;
    }

    public UUID id() {
        return id;
    }

    public UUID blueprintId() {
        return blueprintId;
    }

    public String blueprintName() {
        return blueprintName;
    }

    public UUID world() {
        return world;
    }

    public int originX() {
        return originX;
    }

    public int originY() {
        return originY;
    }

    public int originZ() {
        return originZ;
    }

    public UUID owner() {
        return owner;
    }

    public String ownerName() {
        return ownerName;
    }

    public UUID company() {
        return company;
    }

    public long paid() {
        return paid;
    }

    public long startedAt() {
        return startedAt;
    }

    public int cursor() {
        return cursor;
    }

    void cursor(int cursor) {
        this.cursor = cursor;
    }

    public int placed() {
        return placed;
    }

    void placed(int placed) {
        this.placed = placed;
    }

    public int solidTotal() {
        return solidTotal;
    }

    public int percent() {
        return solidTotal <= 0 ? 100 : (int) Math.clamp(placed * 100L / solidTotal, 0, 100);
    }

    public boolean done(int volume) {
        return cursor >= volume;
    }
}
