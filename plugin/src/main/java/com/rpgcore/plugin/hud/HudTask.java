package com.rpgcore.plugin.hud;

import com.rpgcore.plugin.RpgCorePlugin;
import com.rpgcore.plugin.data.PlayerData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Action-bar HUD. Runs on its own interval (default once a second) rather than
 * every tick - the bar holds its text for ~3 seconds, so this looks identical
 * while sending 20x fewer packets than the datapack version did, which matters
 * doubly for Bedrock players since Geyser has to translate each one.
 */
public final class HudTask extends BukkitRunnable {

    private final RpgCorePlugin plugin;

    public HudTask(RpgCorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        boolean hud = plugin.rpgConfig().hudEnabled();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            PlayerData data = plugin.players().cached(player.getUniqueId());
            if (data == null) {
                continue;
            }
            if (data.weightTier() >= 2) {
                plugin.weight().applyOverloadEffects(player, data);
            }
            if (hud) {
                player.sendActionBar(render(player, data));
            }
        }
    }

    private Component render(Player player, PlayerData data) {
        String weightColor = switch (data.weightTier()) {
            case 1 -> "&e";
            case 2 -> "&6";
            case 3 -> "&c";
            default -> "&b";
        };
        String text = "&6Lv." + data.level()
                + "  &cHP " + (int) Math.ceil(player.getHealth()) + "&7/&c" + data.maxHealth()
                + "  &aXP " + data.xp() + "&7/&a" + data.xpNeed()
                + "  " + weightColor + "WT " + data.weight() + "&7/" + weightColor + data.weightMax();
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text);
    }
}
