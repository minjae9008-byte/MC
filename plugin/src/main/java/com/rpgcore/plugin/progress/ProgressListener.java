package com.rpgcore.plugin.progress;

import com.rpgcore.plugin.RpgCorePlugin;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerFishEvent;

/**
 * Feeds the lifetime tallies the achievements are written against.
 *
 * Everything here is a single counter bump on an event that already had to
 * fire, so progression costs nothing when nobody is close to a goal. Creative
 * mode is excluded throughout: a goal that can be finished by filling a chest
 * from the creative menu is not a goal.
 */
public final class ProgressListener implements Listener {

    private final RpgCorePlugin plugin;

    public ProgressListener(RpgCorePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        Material type = event.getBlock().getType();
        AchievementService achievements = plugin.achievements();
        achievements.bump(player, CounterType.BLOCKS_MINED, 1);
        if (isOre(type)) {
            achievements.bump(player, CounterType.ORES_MINED, 1);
        }
        if (Tag.LOGS.isTagged(type)) {
            achievements.bump(player, CounterType.LOGS_CHOPPED, 1);
        }
    }

    /**
     * Only a real catch counts. PlayerFishEvent also fires for casting,
     * reeling in nothing and hooking an entity, and none of those are fish.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH
                || !(event.getCaught() instanceof Item)
                || event.getPlayer().getGameMode() == GameMode.CREATIVE) {
            return;
        }
        plugin.achievements().bump(event.getPlayer(), CounterType.FISH_CAUGHT, 1);
    }

    /**
     * Vanilla has no "ore" item tag covering both stone and deepslate variants
     * plus ancient debris, so the block tags are combined here.
     */
    private boolean isOre(Material type) {
        return Tag.COAL_ORES.isTagged(type)
                || Tag.IRON_ORES.isTagged(type)
                || Tag.COPPER_ORES.isTagged(type)
                || Tag.GOLD_ORES.isTagged(type)
                || Tag.REDSTONE_ORES.isTagged(type)
                || Tag.LAPIS_ORES.isTagged(type)
                || Tag.DIAMOND_ORES.isTagged(type)
                || Tag.EMERALD_ORES.isTagged(type)
                || type == Material.NETHER_QUARTZ_ORE
                || type == Material.NETHER_GOLD_ORE
                || type == Material.ANCIENT_DEBRIS;
    }
}
