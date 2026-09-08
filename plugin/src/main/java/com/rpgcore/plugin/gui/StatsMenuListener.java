package com.rpgcore.plugin.gui;

import com.rpgcore.plugin.RpgCorePlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;

public final class StatsMenuListener implements Listener {

    private final RpgCorePlugin plugin;
    private final StatsMenu statsMenu;

    public StatsMenuListener(RpgCorePlugin plugin, StatsMenu statsMenu) {
        this.plugin = plugin;
        this.statsMenu = statsMenu;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof StatsMenu.Holder)) {
            return;
        }
        event.setCancelled(true);

        if (event.getClickedInventory() == null || event.getClickedInventory().getHolder() == null
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
            if (player.isOnline() && player.getOpenInventory().getTopInventory().getHolder() instanceof StatsMenu.Holder) {
                statsMenu.open(player);
            }
        }, 3L);
    }
}
