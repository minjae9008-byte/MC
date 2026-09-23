package com.rpgcore.plugin.progress;

import com.rpgcore.plugin.RpgCorePlugin;
import com.rpgcore.plugin.util.MaterialSets;
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

import java.util.List;
import java.util.Set;

/**
 * Feeds the lifetime tallies the achievements are written against.
 *
 * Everything here is a single counter bump on an event that already had to
 * fire, so progression costs nothing when nobody is close to a goal. Which
 * blocks count as ore or as log is resolved into a set once at load rather
 * than asked of a dozen tags per broken block, and it comes from config so an
 * operator can count their own modded or datapack blocks.
 *
 * Creative mode is excluded throughout: a goal that can be finished from the
 * creative menu is not a goal.
 */
public final class ProgressListener implements Listener {

    private static final List<String> DEFAULT_ORES = List.of(
            "#minecraft:coal_ores", "#minecraft:iron_ores", "#minecraft:copper_ores",
            "#minecraft:gold_ores", "#minecraft:redstone_ores", "#minecraft:lapis_ores",
            "#minecraft:diamond_ores", "#minecraft:emerald_ores",
            "minecraft:nether_quartz_ore", "minecraft:nether_gold_ore", "minecraft:ancient_debris");

    private final RpgCorePlugin plugin;
    private Set<Material> ores = Set.of();
    private Set<Material> logs = Set.of();

    public ProgressListener(RpgCorePlugin plugin) {
        this.plugin = plugin;
        load();
    }

    /** Re-resolves the block sets; called again by /rpgcore reload. */
    public void load() {
        List<String> oreIds = plugin.getConfig().getStringList("progress.ore-blocks");
        List<String> logIds = plugin.getConfig().getStringList("progress.log-blocks");
        ores = MaterialSets.resolve(plugin, Tag.REGISTRY_BLOCKS,
                oreIds.isEmpty() ? DEFAULT_ORES : oreIds, "progress.ore-blocks");
        logs = logIds.isEmpty()
                ? Set.copyOf(Tag.LOGS.getValues())
                : MaterialSets.resolve(plugin, Tag.REGISTRY_BLOCKS, logIds, "progress.log-blocks");
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
        // A block out of the ground is one more of that thing in the world,
        // so the market's supply of it goes up - which is the live half of
        // the rule that mining speed sets supply. One block barely moves a
        // warehouse; a server mining all evening does.
        plugin.market().recordProduction(type);
        if (ores.contains(type)) {
            achievements.bump(player, CounterType.ORES_MINED, 1);
        }
        if (logs.contains(type)) {
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

    public int oreCount() {
        return ores.size();
    }

    public int logCount() {
        return logs.size();
    }
}
