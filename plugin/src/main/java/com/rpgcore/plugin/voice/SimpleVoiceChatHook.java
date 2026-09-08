package com.rpgcore.plugin.voice;

import com.rpgcore.plugin.RpgCorePlugin;

/**
 * Soft integration point for the "Simple Voice Chat" plugin
 * (https://modrepo.de/minecraft/voicechat), which is what actually provides
 * real proximity VOICE audio - a datapack, and a plain Bukkit plugin without
 * it, cannot capture or route microphone audio on their own.
 *
 * If Simple Voice Chat isn't installed this is a complete no-op: RPGCore's
 * datapack and its own proximity TEXT chat (see chat/ProximityChatListener)
 * keep working fine without it. If it IS installed, actual voice distance is
 * configured in plugins/voicechat/voicechat-server.properties
 * (voice_chat_distance) - see README.md for the recommended value to match
 * config.yml's proximity-chat.range. All SVC-API-touching code lives in
 * {@link SimpleVoiceChatIntegration}, loaded only when the plugin is present,
 * so a missing/incompatible SVC install can never break the rest of RPGCore.
 */
public final class SimpleVoiceChatHook {

    private final RpgCorePlugin plugin;

    public SimpleVoiceChatHook(RpgCorePlugin plugin) {
        this.plugin = plugin;
    }

    public void tryHook() {
        if (plugin.getServer().getPluginManager().getPlugin("voicechat") == null) {
            plugin.getLogger().info("Simple Voice Chat not found - real proximity VOICE chat is unavailable. "
                    + "RPGCore's text-based proximity chat still works. See README.md to install it.");
            return;
        }
        if (!plugin.getConfig().getBoolean("voicechat.sync-range-with-proximity-chat", true)) {
            return;
        }
        try {
            SimpleVoiceChatIntegration.register(plugin);
            plugin.getLogger().info("Hooked into Simple Voice Chat.");
        } catch (Throwable t) {
            // Any API mismatch (e.g. after an SVC update) degrades gracefully -
            // the rest of RPGCore is completely unaffected.
            plugin.getLogger().warning("Simple Voice Chat was found but the integration failed to "
                    + "register (API mismatch?). Voice chat itself still works via its own config; "
                    + "only the automatic range-sync is skipped: " + t);
        }
    }
}
