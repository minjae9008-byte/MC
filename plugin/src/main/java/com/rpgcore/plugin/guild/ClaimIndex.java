package com.rpgcore.plugin.guild;

import org.bukkit.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * "Which claim, if any, covers this block?" - answered without walking the
 * list of every claim on the server.
 *
 * This is asked on BlockBreakEvent, which is one of the hottest events there
 * is: every block every player mines, plus every block a chain fell breaks.
 * A linear scan would be fine with five claims and a real cost with five
 * hundred, and the failure mode of getting that wrong is a server that runs
 * fine in testing and stutters once the feature is popular.
 *
 * So claims are filed under every chunk they touch. A claim of radius 32
 * occupies twenty-five chunk buckets, which is a trade of a little memory for
 * a lookup that is one hash and a list of nearly always zero or one entries.
 */
final class ClaimIndex {

    /** world -> chunk key -> the claims touching that chunk. */
    private final Map<UUID, Map<Long, List<GuildClaim>>> byWorld = new HashMap<>();
    private int size;

    void clear() {
        byWorld.clear();
        size = 0;
    }

    int size() {
        return size;
    }

    void add(GuildClaim claim) {
        Map<Long, List<GuildClaim>> world =
                byWorld.computeIfAbsent(claim.worldId(), k -> new HashMap<>());
        forEachChunk(claim, key -> world.computeIfAbsent(key, k -> new ArrayList<>(1)).add(claim));
        size++;
    }

    void remove(GuildClaim claim) {
        Map<Long, List<GuildClaim>> world = byWorld.get(claim.worldId());
        if (world == null) {
            return;
        }
        boolean[] removed = {false};
        forEachChunk(claim, key -> {
            List<GuildClaim> bucket = world.get(key);
            if (bucket == null) {
                return;
            }
            if (bucket.remove(claim)) {
                removed[0] = true;
            }
            if (bucket.isEmpty()) {
                world.remove(key);
            }
        });
        // Counted only when something actually came out. Several paths remove
        // a claim - a broken banner, an explosion, a disband, the rolling
        // validity check - and two of them reaching the same claim would
        // otherwise walk this count below the number of claims that exist.
        if (removed[0]) {
            size--;
        }
    }

    /** The claim covering these coordinates, or null. */
    GuildClaim at(World world, int x, int z) {
        Map<Long, List<GuildClaim>> claims = byWorld.get(world.getUID());
        if (claims == null) {
            return null;
        }
        List<GuildClaim> bucket = claims.get(chunkKey(x >> 4, z >> 4));
        if (bucket == null) {
            return null;
        }
        for (GuildClaim claim : bucket) {
            if (claim.covers(world, x, z)) {
                return claim;
            }
        }
        return null;
    }

    /**
     * Every claim in a world, for the overlap check when a new flag goes up.
     *
     * A claim sits in one bucket per chunk it touches, so the same one comes
     * back many times over - twenty-five times at the default radius. The
     * de-duplication is a hash set rather than a scan of what has been kept so
     * far: with a few claims the difference is nothing, and with a few hundred
     * the scan turns planting one banner into millions of comparisons.
     * GuildClaim does not override equals, so a HashSet is identity anyway,
     * which is the comparison this wants.
     */
    Set<GuildClaim> allIn(UUID worldId) {
        Map<Long, List<GuildClaim>> claims = byWorld.get(worldId);
        if (claims == null) {
            return Set.of();
        }
        Set<GuildClaim> unique = new HashSet<>();
        for (List<GuildClaim> bucket : claims.values()) {
            unique.addAll(bucket);
        }
        return unique;
    }

    private void forEachChunk(GuildClaim claim, java.util.function.LongConsumer action) {
        int minChunkX = (claim.x() - claim.radius()) >> 4;
        int maxChunkX = (claim.x() + claim.radius()) >> 4;
        int minChunkZ = (claim.z() - claim.radius()) >> 4;
        int maxChunkZ = (claim.z() + claim.radius()) >> 4;
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                action.accept(chunkKey(cx, cz));
            }
        }
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }
}
