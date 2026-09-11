package com.rpgcore.plugin.weight;

import com.rpgcore.plugin.RpgCorePlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

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

    public WeightLore(RpgCorePlugin plugin, ItemWeightTable table) {
        this.plugin = plugin;
        this.table = table;
        this.markerKey = new NamespacedKey(plugin, "weight_lore");
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
        // The overwhelmingly common case, and the one that has to stay cheap:
        // a plain item while the feature is off never needs its meta read.
        if (!enabled && !stack.hasItemMeta()) {
            return false;
        }

        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return false;
        }
        String previous = meta.getPersistentDataContainer().get(markerKey, PersistentDataType.STRING);
        String line = enabled ? render(stack) : null;
        if (previous == null && line == null) {
            return false;
        }
        if (line != null && line.equals(previous)) {
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

    private String render(ItemStack stack) {
        return plugin.rpgConfig().weightLoreFormat()
                .replace("%weight%", String.valueOf(table.weightOf(stack.getType())));
    }
}
