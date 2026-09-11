package com.rpgcore.plugin.party;

import com.rpgcore.plugin.RpgCorePlugin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.projectiles.ProjectileSource;

/**
 * Party side effects: keeping members from hurting each other, and letting a
 * party fall away once everyone in it has logged off.
 */
public final class PartyListener implements Listener {

    private final RpgCorePlugin plugin;

    public PartyListener(RpgCorePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (plugin.rpgConfig().partyFriendlyFire() || !(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = attackerOf(event);
        if (attacker == null || attacker.equals(victim)) {
            return;
        }
        if (plugin.parties().sameParty(attacker, victim)) {
            event.setCancelled(true);
        }
    }

    /** Resolves arrows and other projectiles back to the player who fired them. */
    private Player attackerOf(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player direct) {
            return direct;
        }
        if (event.getDamager() instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player player) {
                return player;
            }
        }
        return null;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        plugin.parties().handleQuit(event.getPlayer());
    }
}
