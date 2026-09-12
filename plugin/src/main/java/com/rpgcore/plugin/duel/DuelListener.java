package com.rpgcore.plugin.duel;

import com.rpgcore.plugin.RpgCorePlugin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Turns the killing blow of a duel into a result instead of a death.
 *
 * The check runs at HIGHEST, after every plugin that reduces damage (armour
 * plugins, protection regions) has had its say, so "would this have killed
 * them" is asked of the number that would really have landed. When the answer
 * is yes the damage is cancelled outright: the loser walks away, and the only
 * thing that changes hands is the stake.
 */
public final class DuelListener implements Listener {

    private final RpgCorePlugin plugin;

    public DuelListener(RpgCorePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = attackerOf(event);
        if (attacker == null || attacker.equals(victim) || !plugin.duels().inDuel(victim, attacker)) {
            return;
        }
        DuelSession session = plugin.duels().sessionOf(victim);
        if (session == null || session.finished()) {
            return;
        }
        // getHealth() is still the pre-hit value here, and getFinalDamage() is
        // what the hit would really take off after armour and effects.
        if (event.getFinalDamage() < victim.getHealth()) {
            return;
        }
        event.setCancelled(true);
        plugin.duels().finish(session, attacker, victim, "");
    }

    /** Resolves arrows and other projectiles back to the player who fired them. */
    private Player attackerOf(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player direct) {
            return direct;
        }
        if (event.getDamager() instanceof Projectile projectile
                && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        return null;
    }

    /**
     * Dying to something else mid-duel - lava, a creeper, a fall - still ends
     * it. Without this the stake would sit in escrow with no result to settle
     * it, and the survivor would be left swinging at a ghost.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        DuelSession session = plugin.duels().sessionOf(player);
        if (session == null || session.finished()) {
            return;
        }
        Player opponent = plugin.getServer().getPlayer(session.opponentOf(player.getUniqueId()));
        if (opponent != null && opponent.isOnline()) {
            plugin.duels().finish(session, opponent, player, "");
        } else {
            plugin.duels().draw(session, "상대가 사라져 무승부입니다.");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        plugin.duels().handleQuit(event.getPlayer());
    }
}
