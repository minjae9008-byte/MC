package com.rpgcore.plugin.blueprint;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A captured building: every block of it, in order, with nothing else.
 *
 * Stored as a palette plus one index per position rather than as a list of
 * blocks. A wall of stone is thousands of positions pointing at one palette
 * entry, and the file ends up a fraction of the size - which matters because
 * a plugin that writes its own files cannot afford a 50,000-line YAML list
 * per saved building.
 *
 * Positions are ordered y first, then z, then x. That is not arbitrary: it is
 * the order the site builds in, so construction rises layer by layer the way
 * a building actually goes up, and the cursor into this array is the only
 * progress state a site needs to survive a restart.
 */
public final class Blueprint {

    /** Palette slot 0 is always air, which is never placed and never charged. */
    public static final int AIR = 0;

    private final UUID id;
    private String name;
    private final UUID owner;
    private final String ownerName;
    private final int width;
    private final int height;
    private final int length;
    /** Block data strings, e.g. "minecraft:oak_stairs[facing=east,...]". */
    private final List<String> palette;
    private final int[] blocks;
    private final long createdAt;

    /** Parsed on first use, because parsing 50,000 strings is not free. */
    private BlockData[] parsed;
    private Map<Material, Integer> tally;

    public Blueprint(UUID id, String name, UUID owner, String ownerName,
                     int width, int height, int length,
                     List<String> palette, int[] blocks, long createdAt) {
        this.id = id;
        this.name = name;
        this.owner = owner;
        this.ownerName = ownerName;
        this.width = width;
        this.height = height;
        this.length = length;
        this.palette = new ArrayList<>(palette);
        this.blocks = blocks;
        this.createdAt = createdAt;
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public void name(String name) {
        this.name = name;
    }

    public UUID owner() {
        return owner;
    }

    public String ownerName() {
        return ownerName;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int length() {
        return length;
    }

    public long createdAt() {
        return createdAt;
    }

    public List<String> palette() {
        return palette;
    }

    public int[] blocks() {
        return blocks;
    }

    public int volume() {
        return blocks.length;
    }

    /** Positions that actually place something. The rest is empty space. */
    public int solidCount() {
        int solid = 0;
        for (int index : blocks) {
            if (index != AIR) {
                solid++;
            }
        }
        return solid;
    }

    public int paletteAt(int position) {
        return blocks[position];
    }

    /** The x/y/z of a position, relative to the blueprint's own corner. */
    public int xOf(int position) {
        return position % width;
    }

    public int yOf(int position) {
        return position / (width * length);
    }

    public int zOf(int position) {
        return (position / width) % length;
    }

    /**
     * The palette, parsed into real block data.
     *
     * An entry this server cannot parse - a block from a version or a datapack
     * that is no longer here - becomes air rather than failing the whole
     * blueprint. A hole in a building is recoverable; a blueprint that will
     * not load at all is not.
     */
    public BlockData[] parsedPalette() {
        if (parsed != null) {
            return parsed;
        }
        BlockData[] out = new BlockData[palette.size()];
        for (int i = 0; i < palette.size(); i++) {
            try {
                out[i] = Bukkit.createBlockData(palette.get(i));
            } catch (IllegalArgumentException e) {
                out[i] = null;
            }
        }
        parsed = out;
        return out;
    }

    /** How many of each material it takes to build, counted once and kept. */
    public Map<Material, Integer> materials() {
        if (tally != null) {
            return tally;
        }
        BlockData[] data = parsedPalette();
        int[] perPalette = new int[palette.size()];
        for (int index : blocks) {
            perPalette[index]++;
        }
        Map<Material, Integer> counts = new EnumMap<>(Material.class);
        for (int i = 1; i < perPalette.length; i++) {
            if (perPalette[i] <= 0 || data[i] == null) {
                continue;
            }
            counts.merge(data[i].getMaterial(), perPalette[i], Integer::sum);
        }
        // Ordered biggest first: that is the order a cost breakdown wants to
        // be read in, and it is stable, so the screen does not reshuffle.
        List<Map.Entry<Material, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort(Map.Entry.<Material, Integer>comparingByValue().reversed());
        Map<Material, Integer> ordered = new LinkedHashMap<>();
        for (Map.Entry<Material, Integer> entry : sorted) {
            ordered.put(entry.getKey(), entry.getValue());
        }
        tally = ordered;
        return tally;
    }

    /** The material that best represents this building, for an icon. */
    public Material icon() {
        for (Map.Entry<Material, Integer> entry : materials().entrySet()) {
            if (entry.getKey().isItem()) {
                return entry.getKey();
            }
        }
        return Material.BRICKS;
    }
}
