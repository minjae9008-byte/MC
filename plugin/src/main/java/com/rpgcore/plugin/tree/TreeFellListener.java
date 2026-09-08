package com.rpgcore.plugin.tree;

import com.rpgcore.plugin.RpgCorePlugin;
import org.bukkit.GameMode;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

public final class TreeFellListener implements Listener {

    private final RpgCorePlugin plugin;
    private final TreeFellService service;

    public TreeFellListener(RpgCorePlugin plugin, TreeFellService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!plugin.rpgConfig().treeFellEnabled()) {
            return;
        }
        Block block = event.getBlock();
        // Skip the events we fire ourselves for protection checks.
        if (service.isSelfBroken(block)) {
            return;
        }

        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        if (plugin.rpgConfig().treeFellSneakDisables() && player.isSneaking()) {
            return;
        }
        if (!service.isLog(block.getType())) {
            return;
        }

        ItemStack tool = player.getInventory().getItemInMainHand();
        if (!service.isTool(tool.getType())) {
            return;
        }

        service.start(player, block, tool);
    }
}
