package com.rpgcore.plugin.util;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;

import java.util.ArrayList;

/**
 * Attribute access that survives Mojang's renames.
 *
 * Attribute keys were flattened in 1.21.2 (minecraft:generic.max_health ->
 * minecraft:max_health) and the Bukkit enum constants moved with them, so we
 * look attributes up through the registry by key and accept either spelling
 * instead of hard-referencing enum constants that may not exist.
 */
public final class Attributes {

    private Attributes() {
    }

    public static Attribute maxHealth() {
        return resolve("max_health", "generic.max_health");
    }

    public static Attribute attackDamage() {
        return resolve("attack_damage", "generic.attack_damage");
    }

    public static Attribute attackSpeed() {
        return resolve("attack_speed", "generic.attack_speed");
    }

    public static Attribute movementSpeed() {
        return resolve("movement_speed", "generic.movement_speed");
    }

    public static Attribute jumpStrength() {
        return resolve("jump_strength", "generic.jump_strength");
    }

    public static Attribute luck() {
        return resolve("luck", "generic.luck");
    }

    private static Attribute resolve(String... keys) {
        for (String key : keys) {
            Attribute attribute = Registry.ATTRIBUTE.get(NamespacedKey.minecraft(key));
            if (attribute != null) {
                return attribute;
            }
        }
        return null;
    }

    /**
     * Replaces (or removes, when amount is 0) a keyed modifier. Modifiers are
     * only touched when the value actually changes, so callers can invoke this
     * freely without churning attribute state every tick.
     */
    public static void setModifier(Player player, Attribute attribute, NamespacedKey key,
                                   double amount, AttributeModifier.Operation operation) {
        if (attribute == null) {
            return;
        }
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance == null) {
            return;
        }

        AttributeModifier existing = null;
        for (AttributeModifier modifier : new ArrayList<>(instance.getModifiers())) {
            if (key.equals(modifier.getKey())) {
                existing = modifier;
                break;
            }
        }

        if (existing != null) {
            if (existing.getAmount() == amount && existing.getOperation() == operation) {
                return;
            }
            instance.removeModifier(existing);
        }
        if (amount != 0.0D) {
            instance.addModifier(new AttributeModifier(key, amount, operation));
        }
    }

    public static void removeModifier(Player player, Attribute attribute, NamespacedKey key) {
        setModifier(player, attribute, key, 0.0D, AttributeModifier.Operation.ADD_NUMBER);
    }

    public static void setBase(Player player, Attribute attribute, double value) {
        if (attribute == null) {
            return;
        }
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance != null && instance.getBaseValue() != value) {
            instance.setBaseValue(value);
        }
    }
}
