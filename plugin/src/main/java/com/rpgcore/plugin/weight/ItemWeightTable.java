package com.rpgcore.plugin.weight;

import com.rpgcore.plugin.RpgCorePlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.configuration.ConfigurationSection;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Material -> weight lookup, resolved once at startup into an EnumMap so the
 * weight scan is a plain array read per item stack (this is the part that used
 * to be 164 `execute if items` commands per player, twice a second).
 *
 * Classification is data-driven: the datapack's #rpgcore:weight_* item tags are
 * preferred (they can reference vanilla tags like #minecraft:planks and the
 * server resolves them for us), with config.yml lists as the fallback when the
 * datapack is not installed.
 */
public final class ItemWeightTable {

    private final RpgCorePlugin plugin;
    private final Map<Material, Integer> weights = new EnumMap<>(Material.class);
    private int defaultWeight;

    public ItemWeightTable(RpgCorePlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        weights.clear();
        defaultWeight = plugin.getConfig().getInt("weight.default-item-weight", 1);

        ConfigurationSection tiers = plugin.getConfig().getConfigurationSection("weight.tiers");
        if (tiers == null) {
            plugin.getLogger().warning("weight.tiers missing from config.yml - every item weighs "
                    + defaultWeight + ".");
            return;
        }

        int fromTags = 0;
        int fromConfig = 0;
        for (String tierName : tiers.getKeys(false)) {
            int value = tiers.getInt(tierName + ".weight", defaultWeight);

            int applied = applyDatapackTag(tierName, value);
            if (applied > 0) {
                fromTags += applied;
                continue;
            }
            fromConfig += applyConfigList(tiers.getStringList(tierName + ".items"), value);
        }

        plugin.getLogger().info("Weight table loaded: " + weights.size() + " materials ("
                + fromTags + " from datapack tags, " + fromConfig + " from config.yml).");
    }

    /**
     * Reads #rpgcore:weight_&lt;tier&gt; from the server's item-tag registry, which
     * includes datapack-defined tags. Returns how many materials were applied,
     * or 0 when the tag is absent (datapack not installed).
     */
    private int applyDatapackTag(String tierName, int value) {
        try {
            NamespacedKey key = new NamespacedKey("rpgcore", "weight_" + tierName.toLowerCase(Locale.ROOT));
            Tag<Material> tag = Bukkit.getTag(Tag.REGISTRY_ITEMS, key, Material.class);
            if (tag == null) {
                return 0;
            }
            int count = 0;
            for (Material material : tag.getValues()) {
                weights.put(material, value);
                count++;
            }
            return count;
        } catch (Throwable t) {
            // Some server builds reject unknown tag lookups instead of
            // returning null - fall back to the config list.
            return 0;
        }
    }

    private int applyConfigList(List<String> ids, int value) {
        int count = 0;
        for (String id : ids) {
            Material material = Material.matchMaterial(id);
            if (material == null) {
                plugin.getLogger().warning("Unknown material in weight config: " + id);
                continue;
            }
            weights.put(material, value);
            count++;
        }
        return count;
    }

    public int weightOf(Material material) {
        Integer value = weights.get(material);
        return value == null ? defaultWeight : value;
    }
}
