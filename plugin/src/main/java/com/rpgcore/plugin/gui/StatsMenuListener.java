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
        if (!(event.getInventory().getHolder() instanceof StatsMenu.Holder)) {
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
        if (slot == StatsMenu.CLOSE_SLOT) {
            player.closeInventory();
            return;
        }

        StatType type = StatsMenu.statAt(slot);
        if (type == null) {
            return;
        }

        // Applied synchronously by the plugin, so the menu can be redrawn
        // immediately - no waiting a tick for a datapack to pick up a trigger.
        if (plugin.stats().allocate(player, type)) {
            plugin.statsMenu().open(player);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof StatsMenu.Holder) {
            event.setCancelled(true);
        }
    }
}
