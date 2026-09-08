package com.rpgcore.plugin.anvil;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;

import java.util.List;
import java.util.Set;

/**
 * One custom anvil recipe: put a piece of gear in the left slot and the
 * ingredient in the right, and get back the same item repaired and/or with an
 * enchantment bumped by one step.
 *
 * @param id               config key, used in messages and logs
 * @param displayName      what the player sees on the result's lore
 * @param targets          materials accepted in the left slot
 * @param ingredient       material required in the right slot
 * @param ingredientAmount how many of it are consumed
 * @param levelCost        experience levels charged by the anvil
 * @param repairPercent    durability restored, as a percentage of the maximum
 * @param grants           enchantment steps this recipe applies
 */
public record AnvilRecipe(String id,
                          String displayName,
                          Set<Material> targets,
                          Material ingredient,
                          int ingredientAmount,
                          int levelCost,
                          int repairPercent,
                          List<Grant> grants) {

    /**
     * One enchantment step.
     *
     * @param enchantment what to add
     * @param levels      how much to add per craft
     * @param maxLevel    ceiling; defaults to the enchantment's vanilla maximum
     */
    public record Grant(Enchantment enchantment, int levels, int maxLevel) {
    }

    public boolean accepts(Material target) {
        return targets.contains(target);
    }
}
