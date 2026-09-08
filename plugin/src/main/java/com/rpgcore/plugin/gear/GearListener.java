package com.rpgcore.plugin.gear;

import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;
import com.rpgcore.plugin.RpgCorePlugin;
import com.rpgcore.plugin.data.PlayerData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;

/**
 * Marks a player's gear dirty whenever the equipped items - or their remaining
 * durability - can have changed. Like {@link com.rpgcore.plugin.weight.WeightListener}
 * nothing is computed here; the tick pump picks the flag up, so a burst of
 * durability ticks still costs one recompute.
 *
 * Inventory-shaped changes (clicking armour into place, closing a chest) are
 * already covered by WeightListener, which marks both flags.
 */
public final class GearListener implements Listener {

    private final RpgCorePlugin plugin;

    public GearListener(RpgCorePlugin plugin) {
        this.plugin = plugin;
    }

    private void mark(Player player) {
        PlayerData data = plugin.players().cached(player.getUniqueId());
        if (data != null) {
            data.markGearDirty();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onHeld(PlayerItemHeldEvent event) {
        mark(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(PlayerItemDamageEvent event) {
        mark(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBreak(PlayerItemBreakEvent event) {
        mark(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onArmorChange(PlayerArmorChangeEvent event) {
        mark(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        mark(event.getPlayer());
    }
}
