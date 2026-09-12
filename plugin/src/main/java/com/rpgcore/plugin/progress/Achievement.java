package com.rpgcore.plugin.progress;

import org.bukkit.Material;

/**
 * One goal, as written in achievements.yml. Everything an achievement does is
 * data, so adding one needs no code.
 *
 * @param id          config key, also the id of the title it grants
 * @param display     what the player is told they achieved
 * @param description one line of flavour for the menu
 * @param icon        menu icon
 * @param counter     the tally it is measured against
 * @param goal        the value that tally has to reach
 * @param firstOnly   true when only the first player on the server may claim it
 * @param title       title text to grant, or null for a reward-only goal
 * @param rewardGold  gold paid out once, on completion
 * @param rewardXp    RPG XP paid out once, on completion
 */
public record Achievement(String id,
                          String display,
                          String description,
                          Material icon,
                          CounterType counter,
                          int goal,
                          boolean firstOnly,
                          String title,
                          int rewardGold,
                          int rewardXp) {

    public boolean grantsTitle() {
        return title != null && !title.isBlank();
    }
}
