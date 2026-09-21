package com.rpgcore.plugin.weight;

import com.rpgcore.plugin.RpgCorePlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Writes an item's carry weight into its own tooltip.
 *
 * Two rules make this safe to do to live items. The line depends on the
 * material and nothing else - never on the stack size - so two stacks of the
 * same thing always end up with byte-identical lore and still stack together.
 * And the exact line written is remembered on the item, so refreshing it after
 * a config change replaces that one line and leaves lore from other plugins,
 * or from the player's own anvil renaming, untouched.
 */
public final class WeightLore {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final RpgCorePlugin plugin;
    private final ItemWeightTable table;
    /** Holds the exact line this plugin wrote, so it can find it again. */
    private final NamespacedKey markerKey;
    /**
     * The rendered line per material. It depends on nothing else, so building
     * it once beats rebuilding the same string for all 41 carried slots on
     * every inventory change.
     */
    private final Map<Material, String> lines = new EnumMap<>(Material.class);

    public WeightLore(RpgCorePlugin plugin, ItemWeightTable table) {
        this.plugin = plugin;
        this.table = table;
        this.markerKey = new NamespacedKey(plugin, "weight_lore");
    }

    /** Drops the rendered lines, for when the format or the table changed. */
    public void reload() {
        lines.clear();
    }

    /**
     * Brings one stack's weight line up to date - adding it, rewriting it
     * after a config change, or stripping it again once the feature is turned
     * off. Returns true when the stack was changed, which is the caller's cue
     * to write it back into whatever holds it.
     */
    public boolean apply(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return false;
        }
        boolean enabled = plugin.rpgConfig().weightLoreEnabled();
        // Reading the marker off the stack's own data view costs nothing;
        // getItemMeta() copies the whole meta, and this runs for all 41 carried
        // slots every time an inventory changes. So the question "is this line
        // already correct?" is answered before any copy is made, and the copy
        // only happens on the rare stack that actually needs rewriting.
        String previous = stack.getPersistentDataContainer().get(markerKey, PersistentDataType.STRING);
        String line = enabled ? render(stack) : null;
        if (previous == null && line == null) {
            return false;
        }
        if (line != null && line.equals(previous)) {
            return false;
        }

        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return false;
        }
        List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        if (previous != null) {
            // Matched on the text rather than on the line's old position: a
            // player who renamed the item, or another plugin that added lore
            // of its own, may well have moved it.
            String plain = PLAIN.serialize(LEGACY.deserialize(previous));
            lore.removeIf(existing -> plain.equals(PLAIN.serialize(existing)));
        }
        if (line != null) {
            // Lore renders italic by default, which reads as "this is special"
            // - the opposite of what a quiet reference number should look like.
            lore.add(LEGACY.deserialize(line).decoration(TextDecoration.ITALIC, false));
            meta.getPersistentDataContainer().set(markerKey, PersistentDataType.STRING, line);
        } else {
            meta.getPersistentDataContainer().remove(markerKey);
        }
        meta.lore(lore.isEmpty() ? null : lore);
        stack.setItemMeta(meta);
        return true;
    }

    /**
     * True when nothing on this stack's tooltip came from anywhere but here.
     *
     * The market needs this. It only buys plain commodities - a stack with a
     * name or lore on it is somebody's kept item, not a tradable unit - but
     * this class writes a weight line onto every carried stack, so "has lore"
     * on its own would mean the market refused to buy anything at all.
     */
    public boolean onlyOwnLore(ItemStack stack) {
        if (stack == null) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null || !meta.hasLore()) {
            return true;
        }
        String previous = stack.getPersistentDataContainer().get(markerKey, PersistentDataType.STRING);
        if (previous == null) {
            return false;
        }
        List<Component> lore = meta.lore();
        if (lore == null || lore.size() != 1) {
            return false;
        }
        // Compared as plain text for the same reason apply() does: the line
        // may have been re-serialised on its way through the client.
        String plain = PLAIN.serialize(LEGACY.deserialize(previous));
        return plain.equals(PLAIN.serialize(lore.get(0)));
    }

    private String render(ItemStack stack) {
        return lines.computeIfAbsent(stack.getType(), material ->
                plugin.rpgConfig().weightLoreFormat()
                        .replace("%weight%", String.valueOf(table.weightOf(material))));
    }
}
