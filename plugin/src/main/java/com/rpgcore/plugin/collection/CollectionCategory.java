package com.rpgcore.plugin.collection;

import org.bukkit.Material;

import java.util.List;

/**
 * One page of the collection log, as written in collection.yml.
 *
 * @param id          config key, also the id of the title completing it grants
 * @param display     page name
 * @param icon        icon on the category page
 * @param entries     every material that counts, in menu order
 * @param title       title text for completing the page, or null
 * @param rewardGold  gold paid out once, on completion
 * @param rewardXp    RPG XP paid out once, on completion
 */
public record CollectionCategory(String id,
                                 String display,
                                 Material icon,
                                 List<Material> entries,
                                 String title,
                                 int rewardGold,
                                 int rewardXp) {

    public int size() {
        return entries.size();
    }

    public boolean grantsTitle() {
        return title != null && !title.isBlank();
    }
}
