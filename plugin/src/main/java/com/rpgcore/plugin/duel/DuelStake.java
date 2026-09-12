package com.rpgcore.plugin.duel;

import com.rpgcore.plugin.collection.CollectionService;
import org.bukkit.ChatColor;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What one side put up. Either an amount of gold, the stack that was in their
 * hand, or nothing at all - a duel with no wager is still a duel.
 *
 * Both are held here rather than left in the player's inventory: the stake is
 * taken the moment the duel starts, so nobody can drop, trade or spend what
 * they have already bet.
 *
 * An item stake is always the stack that was actually lifted out of the hand,
 * never a copy taken earlier: see {@code DuelService#escrow}. Two items of the
 * same material are not the same wager, so the stack's identity - its
 * enchantments and its wear - is what {@link #describe()} puts in front of the
 * player who is being asked to match it.
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
            String detail = detailOf(item);
            return ChatColor.WHITE + CollectionService.name(item.getType())
                    + ChatColor.GRAY + " x" + item.getAmount()
                    + (detail.isEmpty() ? "" : ChatColor.GRAY + " " + detail);
        }
        return ChatColor.GOLD + String.valueOf(gold) + ChatColor.GRAY + " 골드";
    }

    /**
     * The part of an item that decides whether matching it is a fair trade:
     * what is enchanted onto it, and how worn it is. Without this the player
     * being asked to match a wager cannot tell a pristine enchanted sword from
     * a battered plain one, which is the whole question they are answering.
     */
    private static String detailOf(ItemStack stack) {
        List<String> parts = new ArrayList<>();
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            if (meta.hasDisplayName()) {
                parts.add("'" + ChatColor.stripColor(meta.getDisplayName()) + "'");
            }
            for (Map.Entry<Enchantment, Integer> entry : meta.getEnchants().entrySet()) {
                parts.add(entry.getKey().getKey().getKey() + " " + entry.getValue());
            }
            if (meta instanceof Damageable damageable && damageable.hasDamage()) {
                int max = damageable.hasMaxDamage()
                        ? damageable.getMaxDamage() : stack.getType().getMaxDurability();
                if (max > 0) {
                    parts.add("내구 " + Math.max(0, 100 - damageable.getDamage() * 100 / max) + "%");
                }
            }
        }
        return parts.isEmpty() ? "" : "(" + String.join(", ", parts) + ")";
    }
}
