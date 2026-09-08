package com.rpgcore.plugin.voice;

import com.rpgcore.plugin.RpgCorePlugin;
import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import org.bukkit.Bukkit;

/**
 * NOTE: the Simple Voice Chat plugin API occasionally changes method
 * signatures between releases. This class follows the documented pattern
 * from the official example plugin (github.com/henkelmax/voicechat-api-bukkit)
 * as of the voicechat-api version pinned in pom.xml - if a server runs a
 * newer/older Simple Voice Chat, re-check that repository and adjust this
 * class before shipping. It only registers a presence hook today; extend
 * {@link Plugin#registerEvents} to add real RPG-driven behaviour (e.g. a
 * skill that widens a player's voice broadcast range).
 *
 * This class is only loaded (and its imports only resolved) the first time
 * {@link #register} is actually called, which SimpleVoiceChatHook only does
 * after confirming the "voicechat" plugin is installed - so servers without
 * it never touch this class at all.
 */
final class SimpleVoiceChatIntegration {

    static void register(RpgCorePlugin plugin) {
        BukkitVoicechatService service = Bukkit.getServicesManager().load(BukkitVoicechatService.class);
        if (service == null) {
            throw new IllegalStateException("BukkitVoicechatService was not registered by the voicechat plugin");
        }
        service.registerPlugin(new Plugin(plugin));
    }

    private record Plugin(RpgCorePlugin plugin) implements VoicechatPlugin {

        @Override
        public String getPluginId() {
            return "rpgcore";
        }

        @Override
        public void initialize(VoicechatApi api) {
            // Presence of this call confirms the API handshake succeeded.
        }

        @Override
        public void registerEvents(EventRegistration registration) {
            registration.registerEvent(VoicechatServerStartedEvent.class, event -> {
                VoicechatServerApi serverApi = event.getVoicechat();
                plugin.getLogger().info("Simple Voice Chat server API ready (v" + serverApi.getVersion() + ").");
            });
        }
    }
}
