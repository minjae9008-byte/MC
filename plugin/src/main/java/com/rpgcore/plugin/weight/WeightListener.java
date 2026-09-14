package com.rpgcore.plugin.weight;

import com.rpgcore.plugin.RpgCorePlugin;
import com.rpgcore.plugin.data.PlayerData;
import com.rpgcore.plugin.trade.TradeSession;
import org.bukkit.block.BlockState;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Entity;
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
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
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
        // This fires for every item entity that appears anywhere in the world -
        // mob drops, block drops, dispensers, farms - so it is the busiest
        // path in the plugin, and it does nothing at all when the feature is
        // off. A ground item that was stamped before it was switched off keeps
        // its line until it reaches an inventory, where the scan strips it.
        if (!plugin.weight().lore().enabled()) {
            return;
        }
        ItemStack stack = event.getEntity().getItemStack();
        if (plugin.weight().lore().apply(stack)) {
            event.getEntity().setItemStack(stack);
        }
    }

    /**
     * Same reasoning, for a stack being taken out of a chest or a furnace.
     *
     * Restricted to real storage: the player's own inventory, and containers
     * that belong to a block or an entity. A stack sitting in a menu another
     * plugin built is not carried load - it is that plugin's furniture, and
     * writing a line of lore into it is both wrong and actively breaking for
     * the many plugins that identify their own menu items by their lore.
     * {@link #isCarriedStorage} draws that line.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onClickStampLore(InventoryClickEvent event) {
        Inventory clicked = event.getClickedInventory();
        if (clicked == null || !isCarriedStorage(clicked)) {
            return;
        }
        ItemStack stack = event.getCurrentItem();
        if (plugin.weight().lore().apply(stack)) {
            event.setCurrentItem(stack);
        }
    }

    /**
     * True for inventories whose contents are a player's to carry: their own
     * inventory or ender chest, and block or entity containers (chests,
     * barrels, furnaces, shulkers, hoppers, minecarts, llamas).
     *
     * An inventory built with {@code Bukkit.createInventory} has either no
     * holder or a holder of the creating plugin's own type - including
     * RPGCore's menus, which this therefore excludes without needing to name
     * them - and those never get stamped. Crafting, anvil and enchanting
     * views land here too; items in them came from the player's inventory
     * already stamped, and anything new gets its line on the next scan.
     */
    private boolean isCarriedStorage(Inventory inventory) {
        InventoryHolder holder = inventory.getHolder();
        return holder instanceof Player
                || holder instanceof BlockState
                || holder instanceof DoubleChest
                || holder instanceof Entity;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDrop(PlayerDropItemEvent event) {
        mark(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && canChangeLoad(event.getView().getTopInventory())) {
            mark(player);
        }
    }

    /**
     * False for RPGCore's own read-only menus, where every click is cancelled
     * and nothing a player carries can move. Marking there would cost a
     * forty-one slot rescan for each click on a button. The trade window is
     * the exception - items really do leave a player's inventory through it.
     */
    private boolean canChangeLoad(Inventory top) {
        InventoryHolder holder = top.getHolder();
        return holder instanceof TradeSession || !plugin.isOwnMenu(holder);
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
