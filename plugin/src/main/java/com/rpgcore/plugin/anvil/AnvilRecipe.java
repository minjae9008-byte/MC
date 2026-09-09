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
 * @param levelCost        experience levels charged by the anvil (0 = free)
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
     * Enchantment levels are not capped by this plugin, but they still have to
     * fit in the item's data and stay renderable, so recipes are bounded by
     * this rather than by the enchantment's vanilla maximum.
     */
    public static final int HARD_LEVEL_CEILING = 255;

    /**
     * One enchantment step.
     *
     * @param enchantment what to add
     * @param levels      how much to add per craft
     * @param maxLevel    ceiling; unlimited by default, bounded only by
     *                    {@link #HARD_LEVEL_CEILING}
     */
    public record Grant(Enchantment enchantment, int levels, int maxLevel) {
    }

    public boolean accepts(Material target) {
        return targets.contains(target);
    }
}
