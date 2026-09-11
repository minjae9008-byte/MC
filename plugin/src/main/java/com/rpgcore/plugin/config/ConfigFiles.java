package com.rpgcore.plugin.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

/**
 * Loads RPGCore's yml files with the copies shipped in the jar installed as
 * defaults.
 *
 * The defaults are attached in memory and the operator's file is never
 * rewritten. That matters because the plugin has grown several times: a
 * config written by an older build is missing whole sections, and without a
 * default behind it a missing list reads back empty - which is a feature
 * silently doing nothing rather than an error anyone can see. With defaults
 * attached, an old file keeps working and only the keys it does set win.
 */
public final class ConfigFiles {

    private ConfigFiles() {
    }

    /** Writes the jar's copy if the file is absent, then loads it with defaults. */
    public static FileConfiguration load(Plugin plugin, String name) {
        File file = new File(plugin.getDataFolder(), name);
        if (!file.isFile()) {
            plugin.saveResource(name, false);
        }
        YamlConfiguration loaded = YamlConfiguration.loadConfiguration(file);
        YamlConfiguration defaults = defaults(plugin, name);
        if (defaults != null) {
            loaded.setDefaults(defaults);
        }
        return loaded;
    }

    /** Attaches the jar's copy as defaults to an already-loaded config. */
    public static void applyDefaults(Plugin plugin, FileConfiguration config, String name) {
        YamlConfiguration defaults = defaults(plugin, name);
        if (defaults != null) {
            config.setDefaults(defaults);
        }
    }

    private static YamlConfiguration defaults(Plugin plugin, String name) {
        try (InputStream in = plugin.getResource(name)) {
            if (in == null) {
                return null;
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return YamlConfiguration.loadConfiguration(reader);
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Could not read the bundled " + name + ": " + e.getMessage());
            return null;
        }
    }

    /** True when the file exists on disk, used to spot a first run. */
    public static boolean exists(Plugin plugin, String name) {
        return new File(plugin.getDataFolder(), name).isFile();
    }

    public static void save(Plugin plugin, FileConfiguration config, String name) {
        try {
            config.save(new File(plugin.getDataFolder(), name));
        } catch (IOException e) {
            plugin.getLogger().warning("Could not write " + name + ": " + e.getMessage());
        }
    }
}
