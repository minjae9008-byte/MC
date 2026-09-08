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
 * Server-side proximity text chat: this is the part a pure vanilla datapack
 * genuinely cannot do (datapacks have no access to chat packets). This is
 * intentionally independent from any voice mod - it works standalone.
 *
 * The range should match datapack/data/rpgcore/function/load.mcfunction's
 * $voice_range constant (config.yml: proximity-chat.range) so the in-game
 * "someone is near" hint and the actual chat range agree.
 */
public final class ProximityChatListener implements Listener {

    private final RpgCorePlugin plugin;

    public ProximityChatListener(RpgCorePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onChat(AsyncChatEvent event) {
        Player sender = event.getPlayer();
        double range = plugin.getConfig().getDouble("proximity-chat.range", 24);
        boolean hideOutOfRange = plugin.getConfig().getBoolean("proximity-chat.hide-out-of-range", true);
        String format = plugin.getConfig().getString("proximity-chat.format", "&7[근접] &f%player%&7: &f%message%");

        String plainMessage = PlainTextComponentSerializer.plainText().serialize(event.message());
        String rendered = format.replace("%player%", sender.getName()).replace("%message%", plainMessage);
        Component renderedComponent = LegacyComponentSerializer.legacyAmpersand().deserialize(rendered);

        if (hideOutOfRange) {
            event.viewers().removeIf(viewer -> {
                if (!(viewer instanceof Player p) || p.equals(sender)) {
                    return false;
                }
                if (!p.getWorld().equals(sender.getWorld())) {
                    return true;
                }
                return p.getLocation().distance(sender.getLocation()) > range;
            });
        }

        event.renderer((source, sourceDisplayName, message, viewer) -> renderedComponent);
    }
}
