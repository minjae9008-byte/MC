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
 * @param economy       what the job is worth in the economy rather than in a fight
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
                     Map<String, Double> attributeMul,
                     Economy economy) {

    /**
     * A job's economic side.
     *
     * Jobs used to be about hitting things and carrying things, which left
     * the whole economy - the market, the companies, the building - outside
     * the one choice every player makes about who they are. These are the
     * numbers that put it back inside: what you save on a trade, what your
     * factory makes with you on the payroll, what you are paid, what a
     * building costs you, and what a bank thinks of you.
     *
     * @param marketFeeDiscount percent of market tax the player keeps
     * @param factoryBonus      percent added to their employer's output
     * @param wageBonus         percent added to their own wage
     * @param buildDiscount     percent off a blueprint's labour
     * @param creditBonus       points added to their credit score
     */
    public record Economy(double marketFeeDiscount, double factoryBonus,
                          double wageBonus, double buildDiscount, int creditBonus) {

        public static final Economy NONE = new Economy(0, 0, 0, 0, 0);

        public boolean any() {
            return marketFeeDiscount > 0 || factoryBonus > 0 || wageBonus > 0
                    || buildDiscount > 0 || creditBonus != 0;
        }
    }

    public int statBonus(StatType type) {
        return statBonus.getOrDefault(type, 0);
    }
}
