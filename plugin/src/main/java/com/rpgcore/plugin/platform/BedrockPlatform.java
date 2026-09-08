package com.rpgcore.plugin.platform;

import com.rpgcore.plugin.RpgCorePlugin;
import com.rpgcore.plugin.util.RpgScoreboard;
import org.bukkit.entity.Player;

/**
 * Geyser/Floodgate awareness, kept behind a facade that never imports a
 * Floodgate class itself. Everything that touches the Floodgate/Cumulus API
 * lives in {@link FloodgateBridge}, which is only class-loaded after we have
 * confirmed the plugin is installed - so a server without Geyser (or with an
 * incompatible Floodgate version) never trips over a missing class.
 *
 * If anything at all goes wrong the plugin degrades to the normal chest GUI,
 * which Geyser itself translates into a Bedrock container screen. Bedrock
 * players therefore always get a working menu; the native form is a bonus.
 */
public final class BedrockPlatform {

    private final RpgCorePlugin plugin;
    private boolean floodgatePresent;
    private boolean bridgeUsable;

    public BedrockPlatform(RpgCorePlugin plugin) {
        this.plugin = plugin;
    }

    public void detect() {
        floodgatePresent = plugin.getServer().getPluginManager().getPlugin("floodgate") != null;
        boolean geyserPresent = plugin.getServer().getPluginManager().getPlugin("Geyser-Spigot") != null;
        bridgeUsable = floodgatePresent && plugin.getConfig().getBoolean("bedrock.use-native-forms", true);

        if (floodgatePresent || geyserPresent) {
            plugin.getLogger().info("Geyser/Floodgate detected - Bedrock players are supported"
                    + (bridgeUsable ? " with native form menus." : " with the chest GUI (native forms disabled)."));
        }
    }

    public boolean isFloodgatePresent() {
        return floodgatePresent;
    }

    /** True only for players who joined through Geyser/Floodgate. */
    public boolean isBedrockPlayer(Player player) {
        if (!floodgatePresent) {
            return false;
        }
        try {
            return FloodgateBridge.isBedrockPlayer(player.getUniqueId());
        } catch (Throwable t) {
            floodgatePresent = false;
            bridgeUsable = false;
            plugin.getLogger().warning("Floodgate API call failed; treating everyone as Java from now on: " + t);
            return false;
        }
    }

    /**
     * Tries to show the native Bedrock form. Returns false when it is not
     * applicable or failed, in which case the caller should fall back to the
     * chest GUI.
     */
    public boolean openStatsForm(Player player, RpgScoreboard board) {
        if (!bridgeUsable || !isBedrockPlayer(player)) {
            return false;
        }
        try {
            FloodgateBridge.openStatsForm(plugin, player, board);
            return true;
        } catch (Throwable t) {
            bridgeUsable = false;
            plugin.getLogger().warning("Bedrock form failed (Floodgate/Cumulus API mismatch?), "
                    + "falling back to the chest GUI for Bedrock players: " + t);
            return false;
        }
    }
}
