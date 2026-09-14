package com.rpgcore.plugin.guild;

import org.bukkit.Location;
import org.bukkit.World;

import java.util.UUID;

/**
 * A claimed area: the block the guild's banner stands on, and how far its
 * writ runs.
 *
 * The shape is a square column rather than a sphere, and it is unbounded
 * vertically. A round claim makes "am I inside" a different answer at head
 * height than at the feet, and a claim with a ceiling means an outsider can
 * mine out everything beneath it - neither is what a player means when they
 * point at a flag and say "this is ours".
 */
public final class GuildClaim {

    private final UUID guildId;
    private final UUID worldId;
    private final int x;
    private final int y;
    private final int z;
    /**
     * Not final: a guild widens its own borders by investing gold, and the
     * claim in the index is the same object the guild holds, so growing it has
     * to be a change rather than a replacement.
     */
    private int radius;

    GuildClaim(UUID guildId, UUID worldId, int x, int y, int z, int radius) {
        this.guildId = guildId;
        this.worldId = worldId;
        this.x = x;
        this.y = y;
        this.z = z;
        this.radius = radius;
    }

    public UUID guildId() {
        return guildId;
    }

    public UUID worldId() {
        return worldId;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int z() {
        return z;
    }

    public int radius() {
        return radius;
    }

    void radius(int radius) {
        this.radius = radius;
    }

    /** True when the banner block itself is at these coordinates. */
    public boolean isBannerAt(World world, int bx, int by, int bz) {
        return world.getUID().equals(worldId) && bx == x && by == y && bz == z;
    }

    public boolean covers(World world, int bx, int bz) {
        return world.getUID().equals(worldId)
                && Math.abs(bx - x) <= radius
                && Math.abs(bz - z) <= radius;
    }

    public boolean covers(Location location) {
        return location.getWorld() != null
                && covers(location.getWorld(), location.getBlockX(), location.getBlockZ());
    }

    /** Distance between two claim centres, or -1 when they are in different worlds. */
    public double centreDistance(GuildClaim other) {
        if (!worldId.equals(other.worldId)) {
            return -1.0D;
        }
        double dx = (double) x - other.x;
        double dz = (double) z - other.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    public String describe() {
        return x + ", " + y + ", " + z + " (반경 " + radius + ")";
    }
}
