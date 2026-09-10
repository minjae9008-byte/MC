package com.rpgcore.plugin.voice;

import com.rpgcore.plugin.RpgCorePlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Properties;

/**
 * Optional courtesy check for the Simple Voice Chat plugin.
 *
 * RPGCore never carried voice itself - Simple Voice Chat does, and it owns the
 * real audio distance. All this does is read that distance out of SVC's own
 * config file and warn when it has drifted away from RPGCore's proximity text
 * range, because voice and text carrying different distances is confusing to
 * play with.
 *
 * Reading the file rather than SVC's API keeps RPGCore at zero dependencies:
 * nothing to add to the build, and no way for an SVC update to break us.
 */
public final class VoiceChatHook {

    private static final String SVC_CONFIG = "plugins/voicechat/voicechat-server.properties";
    /** Ranges within this many blocks of each other are treated as matching. */
    private static final double TOLERANCE = 0.5D;

    private final RpgCorePlugin plugin;

    public VoiceChatHook(RpgCorePlugin plugin) {
        this.plugin = plugin;
    }

    public void check() {
        if (plugin.getServer().getPluginManager().getPlugin("voicechat") == null) {
            plugin.getLogger().info("Simple Voice Chat not installed - proximity chat is text only. "
                    + "Install it for real proximity voice (Java clients only).");
            return;
        }
        if (!plugin.getConfig().getBoolean("proximity-chat.warn-voice-range-mismatch", true)) {
            return;
        }

        Double voiceRange = readVoiceRange();
        if (voiceRange == null) {
            return;
        }
        double textRange = plugin.rpgConfig().proximityRange();
        if (Math.abs(voiceRange - textRange) > TOLERANCE) {
            plugin.getLogger().warning("Simple Voice Chat carries " + voiceRange + " blocks but RPGCore's"
                    + " proximity text chat carries " + textRange + ". Match voice_chat_distance in "
                    + SVC_CONFIG + " with proximity-chat.range in RPGCore's config.yml.");
        }
    }

    /** null when the file is missing or unreadable - never a reason to fail. */
    private Double readVoiceRange() {
        File file = new File(SVC_CONFIG);
        if (!file.isFile()) {
            return null;
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file.toPath())) {
            properties.load(in);
        } catch (IOException e) {
            return null;
        }
        try {
            String value = properties.getProperty("voice_chat_distance");
            return value == null ? null : Double.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
