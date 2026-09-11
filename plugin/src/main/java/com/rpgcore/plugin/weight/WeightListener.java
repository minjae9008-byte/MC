package com.rpgcore.plugin.weight;

import com.rpgcore.plugin.RpgCorePlugin;
import com.rpgcore.plugin.data.PlayerData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Marks a player's weight (and gear) dirty whenever their carried items can
 * have changed.
 * Nothing is computed here - {@link WeightService#tick()} picks the flag up on
 * the next tick, so a burst of events (e.g. shift-clicking a full chest) still
 * results in a single recompute.
 */
public final class WeightListener implements Listener {

    private final RpgCorePlugin plugin;

    public WeightListener(RpgCorePlugin plugin) {
        this.plugin = plugin;
    }

    private void mark(Player player) {
        PlayerData data = plugin.players().cached(player.getUniqueId());
        if (data != null) {
            // The same events can move armour and tools around, so the gear
            // condition is recomputed alongside the weight.
            data.markInventoryDirty();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) {
            mark(player);
        }
    }

    /**
     * Stamps the weight tooltip on the way in rather than waiting for the next
     * scan. Two stacks only merge when their lore already matches, so an
     * unstamped stack dropped next to a stamped one - or picked up on top of
     * one - would sit in a slot of its own forever. Stamping an item the
     * moment it enters the world is what keeps the vanilla merge working.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        ItemStack stack = event.getEntity().getItemStack();
        if (plugin.weight().lore().apply(stack)) {
            event.getEntity().setItemStack(stack);
        }
    }

    /** Same reasoning, for a stack being taken out of a chest or a furnace. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onClickStampLore(InventoryClickEvent event) {
        // RPGCore's own menus are furniture, and the trade window's buttons
        // are furniture too - neither is anybody's carried load.
        if (plugin.isOwnMenu(event.getView().getTopInventory().getHolder())) {
            return;
        }
        ItemStack stack = event.getCurrentItem();
        if (plugin.weight().lore().apply(stack)) {
            event.setCurrentItem(stack);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDrop(PlayerDropItemEvent event) {
        mark(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            mark(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            mark(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            mark(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlace(BlockPlaceEvent event) {
        mark(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onConsume(PlayerItemConsumeEvent event) {
        mark(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemBreak(PlayerItemBreakEvent event) {
        mark(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        mark(event.getPlayer());
    }
}
