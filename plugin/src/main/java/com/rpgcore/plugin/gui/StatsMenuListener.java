package com.rpgcore.plugin.gui;

import com.rpgcore.plugin.RpgCorePlugin;
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

        StatsMenu.StatSlot statSlot = StatsMenu.byClickedSlot(slot);
        if (statSlot == null) {
            return;
        }

        plugin.scoreboard().trigger(player, statSlot.triggerObjective());

        // The datapack processes the trigger on its next tick; refresh the GUI
        // shortly after so the player sees the updated numbers without reopening.
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()
                    && player.getOpenInventory().getTopInventory().getHolder() instanceof StatsMenu.Holder) {
                plugin.statsMenu().open(player);
            }
        }, 3L);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof StatsMenu.Holder) {
            event.setCancelled(true);
        }
    }
}
