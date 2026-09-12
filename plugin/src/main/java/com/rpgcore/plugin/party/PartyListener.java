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
 * Party side effects: keeping members from hurting each other, and telling a
 * party when one of its members logs off.
 *
 * Quitting does not dissolve anything. Parties are persistent by design (see
 * {@link PartyService}) so that a group survives a restart, which means the
 * only ways out of one are /party leave, /party kick and /party disband.
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
        // A duel is consented to by both sides, so it outranks the party's
        // standing ceasefire - otherwise partied friends could never settle it.
        if (plugin.duels().inDuel(attacker, victim)) {
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
