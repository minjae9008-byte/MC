package com.rpgcore.plugin.discord;

import com.rpgcore.plugin.RpgCorePlugin;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * The events Discord hears about that no other part of the plugin already
 * passes through. Level-ups and achievements are reported from the services
 * that grant them, because only those know a grant actually happened.
 *
 * Everything here is MONITOR priority and reads state rather than changing it:
 * a notifier must never be the reason an event behaves differently, and at
 * MONITOR the death message is the final one, after any plugin that rewrote it.
 */
public final class DiscordListener implements Listener {

    private final RpgCorePlugin plugin;

    public DiscordListener(RpgCorePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        plugin.notifier().playerJoined(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        plugin.notifier().playerLeft(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        // Vanilla's line already names the cause and the killer; a message
        // built here would only be a worse version of it. It is null when the
        // server or another plugin suppressed the announcement, and in that
        // case the notifier falls back to a plain sentence.
        String message = event.deathMessage() == null
                ? null
                : PlainTextComponentSerializer.plainText().serialize(event.deathMessage());
        plugin.notifier().playerDied(player, message, plugin.players().cached(player.getUniqueId()));
    }
}
