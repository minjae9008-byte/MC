package com.rpgcore.plugin.gui;

import com.rpgcore.plugin.RpgCorePlugin;
import com.rpgcore.plugin.stats.StatType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

public final class StatsMenuListener implements Listener {

    private final RpgCorePlugin plugin;

    public StatsMenuListener(RpgCorePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof StatsMenu.Holder holder)) {
            return;
        }
        // Cancel every interaction with the menu, including shift-clicks from
        // the player's own inventory, so menu items can never be taken out.
        event.setCancelled(true);

        if (event.getClickedInventory() == null
                || !(event.getClickedInventory().getHolder() instanceof StatsMenu.Holder)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        int slot = event.getRawSlot();
        if (slot == holder.closeSlot()) {
            // Closing from inside the click handler leaves the client's cursor
            // state out of sync, so this too waits for the next tick.
            plugin.getServer().getScheduler().runTask(plugin, () -> player.closeInventory());
            return;
        }

        StatType type = StatsMenu.statAt(slot);
        if (type == null) {
            return;
        }

        // The allocation itself is applied straight away - no waiting a tick
        // for a datapack to pick up a trigger - but opening an inventory from
        // inside InventoryClickEvent desyncs the client, so the redraw is
        // scheduled for the next tick.
        if (plugin.stats().allocate(player, type)) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    plugin.statsMenu().open(player);
                }
            });
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof StatsMenu.Holder) {
            event.setCancelled(true);
        }
    }
}
