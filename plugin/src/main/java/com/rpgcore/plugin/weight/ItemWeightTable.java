package com.rpgcore.plugin.weight;

import com.rpgcore.plugin.RpgCorePlugin;
import com.rpgcore.plugin.util.MaterialSets;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.configuration.ConfigurationSection;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * Material -> weight lookup, resolved once at startup into an EnumMap so the
 * weight scan is a plain array read per item stack.
 *
 * Every tier is a plain config list that may mix item ids with tag references
 * (see {@link MaterialSets}), so classifying a whole family of items is one
 * line and needs no datapack.
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

        for (String tier : tiers.getKeys(false)) {
            int value = tiers.getInt(tier + ".weight", defaultWeight);
            Set<Material> materials = MaterialSets.resolve(plugin, Tag.REGISTRY_ITEMS,
                    tiers.getStringList(tier + ".items"), "weight.tiers." + tier);
            for (Material material : materials) {
                weights.put(material, value);
            }
        }

        if (weights.isEmpty()) {
            plugin.getLogger().warning("The weight table is empty - every item will weigh "
                    + defaultWeight + ". Check weight.tiers in config.yml, then /rpgcore check.");
        } else {
            plugin.getLogger().info("Weight table loaded: " + weights.size() + " materials; "
                    + "anything unlisted weighs " + defaultWeight + ".");
        }
    }

    public int size() {
        return weights.size();
    }

    public int weightOf(Material material) {
        Integer value = weights.get(material);
        return value == null ? defaultWeight : value;
    }
}
