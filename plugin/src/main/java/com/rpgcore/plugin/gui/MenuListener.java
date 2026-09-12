package com.rpgcore.plugin.gui;

import com.rpgcore.plugin.RpgCorePlugin;
import com.rpgcore.plugin.collection.CollectionMenu;
import com.rpgcore.plugin.job.JobMenu;
import com.rpgcore.plugin.progress.TitleMenu;
import com.rpgcore.plugin.job.RpgJob;
import com.rpgcore.plugin.stats.StatType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryHolder;

/**
 * Clicks in RPGCore's menus.
 *
 * Every menu here is read-only furniture, so the first thing any click does is
 * get cancelled; what follows is only ever "which button was that". Opening or
 * closing an inventory from inside InventoryClickEvent desyncs the client, so
 * both are scheduled for the next tick.
 */
public final class MenuListener implements Listener {

    private final RpgCorePlugin plugin;

    public MenuListener(RpgCorePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (!plugin.isOwnMenu(holder) || holder instanceof com.rpgcore.plugin.trade.TradeSession) {
            // The trade window is the one RPGCore screen players may put items
            // into, so it has a listener of its own.
            return;
        }
        // Cancel every interaction with the menu, including shift-clicks from
        // the player's own inventory, so menu items can never be taken out.
        event.setCancelled(true);

        if (event.getClickedInventory() == null || event.getClickedInventory().getHolder() != holder) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        if (holder instanceof JobMenu.Holder jobs) {
            RpgJob job = jobs.jobAt(event.getRawSlot());
            if (job != null && plugin.jobs().choose(player, job)) {
                later(player, player::closeInventory);
            }
            return;
        }

        if (holder instanceof TitleMenu.Holder menu) {
            String id = menu.titleAt(event.getRawSlot());
            if (id == null) {
                return;
            }
            if (id.isEmpty()) {
                plugin.titles().wear(player, null);
                player.sendMessage(org.bukkit.ChatColor.YELLOW + "[칭호] 칭호를 뗐습니다.");
            } else if (!plugin.titles().hasEarned(player, id)) {
                return;
            } else {
                plugin.titles().wear(player, id);
                player.sendMessage(org.bukkit.ChatColor.GREEN + "[칭호] "
                        + plugin.titles().byId(id).display()
                        + org.bukkit.ChatColor.GREEN + " 을(를) 착용했습니다.");
            }
            later(player, () -> plugin.titleMenu().open(player));
            return;
        }

        if (holder instanceof CollectionMenu.Holder menu) {
            int slot = event.getRawSlot();
            if (slot == menu.backSlot()) {
                later(player, () -> plugin.collectionMenu().open(player));
                return;
            }
            String category = menu.linkAt(slot);
            if (category != null) {
                later(player, () -> plugin.collectionMenu().openCategory(player, category));
            }
            return;
        }

        if (!(holder instanceof StatsMenu.Holder)) {
            return;
        }

        StatsMenu.Holder stats = (StatsMenu.Holder) holder;
        int slot = event.getRawSlot();
        if (slot == stats.closeSlot()) {
            later(player, player::closeInventory);
            return;
        }
        if (slot == StatsMenu.JOB_SLOT) {
            later(player, () -> plugin.jobMenu().open(player));
            return;
        }

        StatType type = StatsMenu.statAt(slot);
        if (type == null) {
            return;
        }
        // The allocation applies straight away; only the redraw waits a tick.
        if (plugin.stats().allocate(player, type)) {
            later(player, () -> plugin.openStatsMenu(player));
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (plugin.isOwnMenu(holder) && !(holder instanceof com.rpgcore.plugin.trade.TradeSession)) {
            event.setCancelled(true);
        }
    }

    private void later(Player player, Runnable action) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                action.run();
            }
        });
    }
}
