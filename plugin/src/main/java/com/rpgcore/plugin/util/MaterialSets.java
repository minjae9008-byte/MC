package com.rpgcore.plugin.util;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.plugin.Plugin;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Turns a config list of ids into a material set.
 *
 * An entry is either a plain id (<code>minecraft:flint</code>) or a tag
 * reference (<code>#minecraft:enchantable/sword</code>,
 * <code>#rpgcore:gear</code>). Tags are resolved through the server's tag
 * registry, which also holds datapack-defined tags - so operators can classify
 * with a vanilla tag, a tag from this datapack, or a bare id, and none of them
 * needs a code change.
 */
public final class MaterialSets {

    private MaterialSets() {
    }

    /** Resolves into a new set; unknown entries are logged and skipped. */
    public static Set<Material> resolve(Plugin plugin, String registry, Collection<String> ids, String context) {
        Set<Material> materials = EnumSet.noneOf(Material.class);
        addAll(plugin, registry, ids, context, materials);
        return materials;
    }

    /** Resolves into an existing set. Returns how many materials were added. */
    public static int addAll(Plugin plugin, String registry, Collection<String> ids, String context,
                             Set<Material> target) {
        int before = target.size();
        for (String raw : ids) {
            String id = raw.trim();
            if (id.isEmpty()) {
                continue;
            }
            if (id.startsWith("#")) {
                if (!addTag(plugin, registry, id.substring(1), context, target)) {
                    plugin.getLogger().warning(context + ": unknown tag " + id
                            + " (a tag that references an id missing from this server version is dropped whole)");
                }
                continue;
            }
            Material material = Material.matchMaterial(id);
            if (material == null) {
                plugin.getLogger().warning(context + ": unknown material " + id);
                continue;
            }
            target.add(material);
        }
        return target.size() - before;
    }

    private static boolean addTag(Plugin plugin, String registry, String tagId, String context,
                                  Set<Material> target) {
        NamespacedKey key = NamespacedKey.fromString(tagId.toLowerCase(Locale.ROOT));
        if (key == null) {
            plugin.getLogger().warning(context + ": malformed tag name #" + tagId);
            return false;
        }
        try {
            Tag<Material> tag = Bukkit.getTag(registry, key, Material.class);
            if (tag == null) {
                return false;
            }
            target.addAll(tag.getValues());
            return true;
        } catch (Throwable t) {
            // Some server builds throw instead of returning null for an
            // unknown tag; treat that the same as "not present".
            return false;
        }
    }

    /**
     * Prefers this datapack's {@code #rpgcore:<name>} tag and falls back to a
     * config list when the datapack is not installed. Returns the source that
     * was actually used, for the startup log.
     */
    public static String loadTagOrConfig(Plugin plugin, String registry, String tagName,
                                         List<String> fallback, String context, Set<Material> target) {
        if (addTag(plugin, registry, "rpgcore:" + tagName, context, target)) {
            return "datapack tag #rpgcore:" + tagName;
        }
        addAll(plugin, registry, fallback, context, target);
        return "config.yml";
    }
}
