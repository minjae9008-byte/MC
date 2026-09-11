package com.rpgcore.plugin.job;

import com.rpgcore.plugin.stats.StatType;
import org.bukkit.Material;

import java.util.List;
import java.util.Map;

/**
 * One job, as written in config.yml. Everything a job does is data - stat
 * bonuses, attribute modifiers, an XP rate and a carry bonus - so adding a
 * class needs no code.
 *
 * @param id            config key, also what is stored on the player
 * @param displayName   what players see, colour codes already translated
 * @param icon          the item shown in the job menu
 * @param description   lore lines for the menu
 * @param minLevel      RPG level required to take this job
 * @param statBonus     free points added on top of the player's own stats
 * @param weightBonus   extra carry capacity
 * @param xpMultiplier  multiplies XP earned from gameplay
 * @param attributeAdd  attribute id -> flat amount (ADD_NUMBER)
 * @param attributeMul  attribute id -> fraction (MULTIPLY_SCALAR_1)
 */
public record RpgJob(String id,
                     String displayName,
                     Material icon,
                     List<String> description,
                     int minLevel,
                     Map<StatType, Integer> statBonus,
                     int weightBonus,
                     double xpMultiplier,
                     Map<String, Double> attributeAdd,
                     Map<String, Double> attributeMul) {

    public int statBonus(StatType type) {
        return statBonus.getOrDefault(type, 0);
    }
}
