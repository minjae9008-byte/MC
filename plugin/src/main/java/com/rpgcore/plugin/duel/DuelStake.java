package com.rpgcore.plugin.duel;

import com.rpgcore.plugin.collection.CollectionService;
import org.bukkit.ChatColor;
import org.bukkit.inventory.ItemStack;

/**
 * What one side put up. Either an amount of gold, the stack that was in their
 * hand, or nothing at all - a duel with no wager is still a duel.
 *
 * Both are held here rather than left in the player's inventory: the stake is
 * taken the moment the duel starts, so nobody can drop, trade or spend what
 * they have already bet.
 */
public record DuelStake(int gold, ItemStack item) {

    public static final DuelStake NONE = new DuelStake(0, null);

    public boolean isEmpty() {
        return gold <= 0 && item == null;
    }

    public String describe() {
        if (isEmpty()) {
            return ChatColor.GRAY + "걸린 것 없음";
        }
        if (item != null) {
            return ChatColor.WHITE + CollectionService.name(item.getType())
                    + ChatColor.GRAY + " x" + item.getAmount();
        }
        return ChatColor.GOLD + String.valueOf(gold) + ChatColor.GRAY + " 골드";
    }
}
