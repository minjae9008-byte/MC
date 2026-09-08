package com.rpgcore.plugin.util;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;

import java.util.Locale;

/**
 * Enchantment lookup by id, kept in one place because Bukkit moved
 * enchantments into a registry and the old {@code Enchantment.getByKey} path
 * is deprecated on newer servers and the only one that exists on older ones.
 */
public final class Enchantments {

    private Enchantments() {
    }

    /** Accepts "sharpness" or "minecraft:sharpness"; null when unknown. */
    public static Enchantment byId(String id) {
        NamespacedKey key = id.indexOf(':') >= 0
                ? NamespacedKey.fromString(id.toLowerCase(Locale.ROOT))
                : NamespacedKey.minecraft(id.toLowerCase(Locale.ROOT));
        if (key == null) {
            return null;
        }
        try {
            Enchantment fromRegistry = Registry.ENCHANTMENT.get(key);
            if (fromRegistry != null) {
                return fromRegistry;
            }
        } catch (Throwable ignored) {
            // Registry.ENCHANTMENT missing on this build - fall through.
        }
        return legacy(key);
    }

    @SuppressWarnings("deprecation") // the only lookup available on older builds
    private static Enchantment legacy(NamespacedKey key) {
        return Enchantment.getByKey(key);
    }
}
