package com.rpgcore.plugin.trade;

import com.rpgcore.plugin.RpgCorePlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryHolder;

/**
 * Routes inventory events to the trade that owns them, and makes sure every
 * way of walking away from a trade ends it rather than leaving items stranded
 * in a window nobody can see.
 */
public final class TradeListener implements Listener {

    private final RpgCorePlugin plugin;

    public TradeListener(RpgCorePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (event.getInventory().getHolder() instanceof TradeSession session) {
            session.handleClick(event);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (!(holder instanceof TradeSession)) {
            return;
        }
        // A drag can scatter one stack across both halves at once, which the
        // per-slot click rules cannot express, so drags stop at the window.
        for (int slot : event.getRawSlots()) {
            if (slot < TradeSession.SIZE) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof TradeSession session
                && event.getPlayer() instanceof Player player) {
            session.handleClose(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        plugin.trades().endIfTrading(event.getPlayer(), event.getPlayer().getName() + " 이(가) 접속을 종료했습니다.");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        plugin.trades().endIfTrading(event.getEntity(), event.getEntity().getName() + " 이(가) 사망했습니다.");
    }
}
