package com.rpgcore.plugin.guild;

import org.bukkit.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        forEachChunk(claim, key -> {
            List<GuildClaim> bucket = world.get(key);
            if (bucket != null && bucket.remove(claim) && bucket.isEmpty()) {
                world.remove(key);
            }
        });
        size--;
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

    /** Every claim in a world, for the overlap check when a new flag goes up. */
    List<GuildClaim> allIn(UUID worldId) {
        Map<Long, List<GuildClaim>> claims = byWorld.get(worldId);
        if (claims == null) {
            return List.of();
        }
        // A claim sits in many buckets, so the same one comes back repeatedly;
        // identity is enough to thin it out because these are the live objects.
        List<GuildClaim> unique = new ArrayList<>();
        for (List<GuildClaim> bucket : claims.values()) {
            for (GuildClaim claim : bucket) {
                if (!containsSame(unique, claim)) {
                    unique.add(claim);
                }
            }
        }
        return unique;
    }

    private static boolean containsSame(List<GuildClaim> list, GuildClaim claim) {
        for (GuildClaim existing : list) {
            if (existing == claim) {
                return true;
            }
        }
        return false;
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
