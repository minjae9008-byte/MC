package com.rpgcore.plugin.chat;

import com.rpgcore.plugin.RpgCorePlugin;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Server-side proximity text chat: this is the part a datapack genuinely
 * cannot do (datapacks have no access to chat packets). It also replaces the
 * old datapack "someone is nearby" poll, which ran an O(n^2) selector every
 * second whether anyone was talking or not - this only does work when a
 * message is actually sent.
 *
 * Independent from any voice mod, so it works for Bedrock players too.
 */
public final class ProximityChatListener implements Listener {

    private final RpgCorePlugin plugin;

    public ProximityChatListener(RpgCorePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onChat(AsyncChatEvent event) {
        Player sender = event.getPlayer();
        double range = plugin.rpgConfig().proximityRange();
        double rangeSquared = range * range;

        String plainMessage = PlainTextComponentSerializer.plainText().serialize(event.message());
        String rendered = plugin.rpgConfig().proximityFormat()
                .replace("%player%", sender.getName())
                .replace("%message%", plainMessage);
        Component renderedComponent = LegacyComponentSerializer.legacyAmpersand().deserialize(rendered);

        if (plugin.rpgConfig().proximityHideOutOfRange()) {
            event.viewers().removeIf(viewer -> {
                if (!(viewer instanceof Player p) || p.equals(sender)) {
                    return false;
                }
                if (!p.getWorld().equals(sender.getWorld())) {
                    return true;
                }
                // distanceSquared avoids a sqrt per viewer per message.
                return p.getLocation().distanceSquared(sender.getLocation()) > rangeSquared;
            });
        }

        event.renderer((source, sourceDisplayName, message, viewer) -> renderedComponent);
    }
}
