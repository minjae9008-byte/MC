package com.rpgcore.plugin.blueprint;

import com.rpgcore.plugin.RpgCorePlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Reads and writes the blueprint library and the sites being built from it.
 *
 * Two files on purpose. A blueprint is written once and then never again; a
 * construction site changes every tick while it is going up. Keeping them
 * together would mean rewriting a fifty-thousand-block building every second
 * to record that four more blocks of it exist.
 *
 * Blocks are run-length encoded - "1024*0,5*3" - because buildings are mostly
 * runs of the same thing. A wall is one run, and a floor is one run per row.
 * The runs are then packed into lines of about a kilobyte so the file has a
 * sane number of lines whether the building is a solid cube or a mosaic.
 */
final class BlueprintStorage {

    static final String LIBRARY = "blueprints.yml";
    static final String SITES = "construction.yml";

    private static final int LINE_LENGTH = 1000;

    private final RpgCorePlugin plugin;
    private final File libraryFile;
    private final File sitesFile;

    BlueprintStorage(RpgCorePlugin plugin) {
        this.plugin = plugin;
        this.libraryFile = new File(plugin.getDataFolder(), LIBRARY);
        this.sitesFile = new File(plugin.getDataFolder(), SITES);
    }

    List<Blueprint> loadLibrary() {
        List<Blueprint> out = new ArrayList<>();
        if (!libraryFile.isFile()) {
            return out;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(libraryFile);
        ConfigurationSection root = yaml.getConfigurationSection("blueprints");
        if (root == null) {
            return out;
        }
        for (String key : root.getKeys(false)) {
            ConfigurationSection node = root.getConfigurationSection(key);
            if (node == null) {
                continue;
            }
            try {
                int width = node.getInt("width");
                int height = node.getInt("height");
                int length = node.getInt("length");
                List<String> palette = node.getStringList("palette");
                int volume = width * height * length;
                if (width <= 0 || height <= 0 || length <= 0 || palette.isEmpty()) {
                    throw new IllegalArgumentException("size or palette missing");
                }
                int[] blocks = decode(node.getStringList("blocks"), volume);
                out.add(new Blueprint(UUID.fromString(key),
                        node.getString("name", "청사진"),
                        UUID.fromString(node.getString("owner", "")),
                        node.getString("owner-name", "?"),
                        width, height, length, palette, blocks,
                        node.getLong("created-at", System.currentTimeMillis())));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().severe(LIBRARY + ": blueprint " + key + " is malformed ("
                        + e.getMessage() + ") - skipped.");
            }
        }
        return out;
    }

    YamlConfiguration buildLibrary(Collection<Blueprint> blueprints) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of(
                "RPGCore - 저장된 청사진입니다. 플러그인이 씁니다.",
                "blocks 는 '개수*팔레트번호' 를 이어 붙인 것입니다."));
        for (Blueprint blueprint : blueprints) {
            String path = "blueprints." + blueprint.id();
            yaml.set(path + ".name", blueprint.name());
            yaml.set(path + ".owner", blueprint.owner().toString());
            yaml.set(path + ".owner-name", blueprint.ownerName());
            yaml.set(path + ".width", blueprint.width());
            yaml.set(path + ".height", blueprint.height());
            yaml.set(path + ".length", blueprint.length());
            yaml.set(path + ".created-at", blueprint.createdAt());
            yaml.set(path + ".palette", blueprint.palette());
            yaml.set(path + ".blocks", encode(blueprint.blocks()));
        }
        return yaml;
    }

    List<ConstructionSite> loadSites() {
        List<ConstructionSite> out = new ArrayList<>();
        if (!sitesFile.isFile()) {
            return out;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(sitesFile);
        ConfigurationSection root = yaml.getConfigurationSection("sites");
        if (root == null) {
            return out;
        }
        for (String key : root.getKeys(false)) {
            ConfigurationSection node = root.getConfigurationSection(key);
            if (node == null) {
                continue;
            }
            try {
                String company = node.getString("company");
                out.add(new ConstructionSite(UUID.fromString(key),
                        UUID.fromString(node.getString("blueprint", "")),
                        node.getString("blueprint-name", "청사진"),
                        UUID.fromString(node.getString("world", "")),
                        node.getInt("x"), node.getInt("y"), node.getInt("z"),
                        UUID.fromString(node.getString("owner", "")),
                        node.getString("owner-name", "?"),
                        company == null || company.isBlank() ? null : UUID.fromString(company),
                        node.getLong("paid", 0),
                        node.getLong("started-at", System.currentTimeMillis()),
                        node.getInt("cursor", 0), node.getInt("placed", 0),
                        node.getInt("solid-total", 0)));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().severe(SITES + ": construction site " + key
                        + " is malformed (" + e.getMessage() + ") - dropped.");
            }
        }
        return out;
    }

    YamlConfiguration buildSites(Collection<ConstructionSite> sites) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of(
                "RPGCore - 진행 중인 공사 현장입니다. 플러그인이 씁니다."));
        for (ConstructionSite site : sites) {
            String path = "sites." + site.id();
            yaml.set(path + ".blueprint", site.blueprintId().toString());
            yaml.set(path + ".blueprint-name", site.blueprintName());
            yaml.set(path + ".world", site.world().toString());
            yaml.set(path + ".x", site.originX());
            yaml.set(path + ".y", site.originY());
            yaml.set(path + ".z", site.originZ());
            yaml.set(path + ".owner", site.owner().toString());
            yaml.set(path + ".owner-name", site.ownerName());
            yaml.set(path + ".company", site.company() == null ? null : site.company().toString());
            yaml.set(path + ".paid", site.paid());
            yaml.set(path + ".started-at", site.startedAt());
            yaml.set(path + ".cursor", site.cursor());
            yaml.set(path + ".placed", site.placed());
            yaml.set(path + ".solid-total", site.solidTotal());
        }
        return yaml;
    }

    /** Runs of identical palette indexes, packed into kilobyte lines. */
    private static List<String> encode(int[] blocks) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        int runValue = blocks.length > 0 ? blocks[0] : 0;
        int runLength = 0;
        for (int index : blocks) {
            if (index == runValue) {
                runLength++;
                continue;
            }
            append(lines, line, runLength, runValue);
            runValue = index;
            runLength = 1;
        }
        if (runLength > 0) {
            append(lines, line, runLength, runValue);
        }
        if (line.length() > 0) {
            lines.add(line.toString());
        }
        return lines;
    }

    private static void append(List<String> lines, StringBuilder line, int count, int value) {
        if (line.length() > 0) {
            line.append(',');
        }
        line.append(count).append('*').append(value);
        if (line.length() >= LINE_LENGTH) {
            lines.add(line.toString());
            line.setLength(0);
        }
    }

    private static int[] decode(List<String> lines, int volume) {
        int[] blocks = new int[volume];
        int position = 0;
        for (String line : lines) {
            for (String run : line.split(",")) {
                if (run.isBlank()) {
                    continue;
                }
                int star = run.indexOf('*');
                if (star <= 0) {
                    throw new IllegalArgumentException("bad run '" + run + "'");
                }
                int count = Integer.parseInt(run.substring(0, star));
                int value = Integer.parseInt(run.substring(star + 1));
                for (int i = 0; i < count && position < volume; i++) {
                    blocks[position++] = value;
                }
            }
        }
        if (position != volume) {
            throw new IllegalArgumentException("expected " + volume + " blocks, got " + position);
        }
        return blocks;
    }
}
