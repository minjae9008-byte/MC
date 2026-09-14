package com.rpgcore.plugin.tree;

import com.rpgcore.plugin.RpgCorePlugin;
import com.rpgcore.plugin.util.MaterialSets;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Chain tree felling.
 *
 * A BlockBreakEvent hands us the exact block and tool, and the flood fill is a
 * queue drained a few blocks per tick, so even a giant jungle tree never
 * spikes a tick.
 *
 * blocks-per-tick is a budget for the whole server, not for one tree. It used
 * to be per job, which made the setting mean nothing under load: with
 * respect-protection-plugins on, every chained block is a synthetic
 * BlockBreakEvent that every protection plugin on the server has to answer, so
 * N players felling at once cost N x the configured ceiling. The budget is
 * shared here and the starting point rotates, so no job can starve behind the
 * ones in front of it.
 */
public final class TreeFellService {

    private final RpgCorePlugin plugin;
    private final Set<Material> logs = EnumSet.noneOf(Material.class);
    private final Set<Material> tools = EnumSet.noneOf(Material.class);
    private final List<Job> jobs = new ArrayList<>();
    /** Blocks this plugin is breaking itself, so our own events are ignored. */
    private final Set<Block> selfBroken = new HashSet<>();
    /** Where the shared budget starts spending each tick, so jobs take turns. */
    private int rotation;

    /**
     * Chains one player may have running at once. A fell drains in well under
     * a second, so this never refuses a player felling one tree after another;
     * it only stops a single player from queueing unbounded work by breaking
     * logs as fast as the client can send them.
     */
    private static final int MAX_JOBS_PER_PLAYER = 3;

    public TreeFellService(RpgCorePlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        logs.clear();
        tools.clear();
        MaterialSets.addAll(plugin, Tag.REGISTRY_BLOCKS,
                plugin.getConfig().getStringList("tree-felling.logs"), "tree-felling.logs", logs);
        MaterialSets.addAll(plugin, Tag.REGISTRY_ITEMS,
                plugin.getConfig().getStringList("tree-felling.tools"), "tree-felling.tools", tools);
        if (logs.isEmpty() || tools.isEmpty()) {
            plugin.getLogger().warning("Tree felling resolved " + logs.size() + " log types and "
                    + tools.size() + " tools - the feature will do nothing. Check tree-felling.logs"
                    + " and tree-felling.tools in config.yml, then /rpgcore check.");
        } else {
            plugin.getLogger().info("Tree felling: " + logs.size() + " log types, " + tools.size() + " tools.");
        }
    }

    public int logCount() {
        return logs.size();
    }

    public int toolCount() {
        return tools.size();
    }

    public boolean isLog(Material material) {
        return logs.contains(material);
    }

    public boolean isTool(Material material) {
        return tools.contains(material);
    }

    public boolean isSelfBroken(Block block) {
        return selfBroken.contains(block);
    }

    /**
     * Starts a chain fell from the block the player just broke, unless this
     * player already has {@link #MAX_JOBS_PER_PLAYER} running - in which case
     * the block they broke simply stays broken, with no chain behind it.
     */
    public void start(Player player, Block origin, ItemStack tool) {
        int mine = 0;
        for (Job running : jobs) {
            if (running.player.equals(player) && ++mine >= MAX_JOBS_PER_PLAYER) {
                return;
            }
        }
        Job job = new Job(player, origin, tool, origin.getType());
        job.enqueueNeighbours(origin, plugin.rpgConfig().treeFellRadius());
        jobs.add(job);
    }

    /**
     * Drains the tick's shared budget across the running jobs; called once per
     * tick. Finished jobs are retired first so the budget is only ever spent
     * on work that is actually left.
     */
    public void tick() {
        if (jobs.isEmpty()) {
            return;
        }
        int maxBlocks = plugin.rpgConfig().treeFellMaxBlocks();
        int radius = plugin.rpgConfig().treeFellRadius();

        for (Iterator<Job> it = jobs.iterator(); it.hasNext(); ) {
            Job job = it.next();
            if (!job.player.isOnline() || job.broken >= maxBlocks || job.queue.isEmpty() || job.toolBroke) {
                // Only for a player still online: awarding XP to someone who
                // already quit would re-create their cached PlayerData after
                // PlayerQuitEvent unloaded it, leaking the entry for good.
                if (job.broken > 0 && job.player.isOnline()) {
                    plugin.stats().awardXp(job.player, job.broken * plugin.rpgConfig().xpPerTreeLog());
                }
                it.remove();
            }
        }
        if (jobs.isEmpty()) {
            return;
        }

        // One budget for the whole server. Spending always starts one job
        // further along than last tick, so a job at the back of the list still
        // makes progress when the budget runs out before reaching it.
        int budget = plugin.rpgConfig().treeFellPerTick();
        int count = jobs.size();
        rotation = count == 0 ? 0 : (rotation + 1) % count;
        for (int offset = 0; offset < count && budget > 0; offset++) {
            Job job = jobs.get((rotation + offset) % count);
            budget -= drain(job, budget, maxBlocks, radius);
        }
    }

    /** Breaks up to {@code budget} blocks of one job; returns how many it spent. */
    private int drain(Job job, int budget, int maxBlocks, int radius) {
        int spent = 0;
        while (spent < budget && !job.queue.isEmpty() && job.broken < maxBlocks && !job.toolBroke) {
            Block block = job.queue.poll();
            // Same guard as when it was queued: a chunk can unload in the ticks
            // between the two, and reading the block would pull it back in.
            if (block == null
                    || !block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)
                    || block.getType() != job.logType) {
                // Not a block this fell will break, so it costs nothing.
                continue;
            }
            spent++;
            if (!breakBlock(job, block)) {
                break;
            }
            job.enqueueNeighbours(block, radius);
        }
        return spent;
    }

    private boolean breakBlock(Job job, Block block) {
        if (plugin.rpgConfig().treeFellRespectProtection()) {
            // Let protection plugins veto each block exactly as if the player
            // had mined it. Our own listener skips these via selfBroken.
            selfBroken.add(block);
            BlockBreakEvent event = new BlockBreakEvent(block, job.player);
            try {
                Bukkit.getPluginManager().callEvent(event);
                if (event.isCancelled()) {
                    return false;
                }
            } finally {
                selfBroken.remove(block);
            }
        }

        if (plugin.rpgConfig().treeFellDamageTool() && !damageTool(job)) {
            job.toolBroke = true;
            return false;
        }

        // breakNaturally honours the tool's enchantments (silk touch, etc.).
        Material broken = block.getType();
        block.breakNaturally(job.tool);
        job.broken++;
        // Counted here rather than off the synthetic BlockBreakEvent above,
        // because that event only exists when respect-protection-plugins is
        // on. A felled log is a felled log either way, so the tally must not
        // move with a setting that has nothing to do with it.
        plugin.progress().countBlock(job.player, broken);
        return true;
    }

    /** Returns false when the tool would break, so felling stops instead. */
    private boolean damageTool(Job job) {
        if (job.tool == null || job.tool.getType().isAir()) {
            return true;
        }
        ItemMeta meta = job.tool.getItemMeta();
        if (!(meta instanceof Damageable damageable)) {
            return true;
        }
        int max = job.tool.getType().getMaxDurability();
        if (max <= 0) {
            return true;
        }

        // Same odds vanilla uses for Unbreaking: a 1/(level+1) chance that the
        // durability point is actually spent.
        int unbreaking = job.tool.getEnchantmentLevel(Enchantment.UNBREAKING);
        if (unbreaking > 0 && ThreadLocalRandom.current().nextInt(unbreaking + 1) != 0) {
            return true;
        }

        int damage = damageable.getDamage() + 1;
        if (damage >= max) {
            return false;
        }
        damageable.setDamage(damage);
        job.tool.setItemMeta(meta);
        return true;
    }

    private final class Job {
        private final Player player;
        private final ItemStack tool;
        private final Material logType;
        private final Deque<Block> queue = new ArrayDeque<>();
        private final Set<Block> seen = new HashSet<>();
        private final int originX;
        private final int originY;
        private final int originZ;
        private int broken;
        private boolean toolBroke;

        private Job(Player player, Block origin, ItemStack tool, Material logType) {
            this.player = player;
            this.tool = tool;
            this.logType = logType;
            this.originX = origin.getX();
            this.originY = origin.getY();
            this.originZ = origin.getZ();
            this.seen.add(origin);
        }

        /**
         * Spreads to all 26 neighbours, which is what real trees need: oak and
         * jungle canopies, and acacia trunks especially, run sideways as much
         * as up, and an upward-only fill leaves most of the tree standing.
         *
         * Two bounds keep it from running away instead:
         *   - nothing below the block that was broken, so a log floor or the
         *     stump of a neighbouring tree is never eaten from above;
         *   - a horizontal radius around the origin, so a row of logs cannot
         *     carry the fell along into the next tree. max-blocks is the final
         *     backstop.
         */
        private void enqueueNeighbours(Block block, int radius) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        Block next = block.getRelative(dx, dy, dz);
                        // Cheap tests first, and getType() last of all: reading
                        // a block's type in a chunk that is not loaded pulls
                        // that chunk in synchronously, and a tree on a chunk
                        // border touches its neighbours on every ring. A tree
                        // does not grow into unloaded ground, so skipping those
                        // costs nothing and saves the load.
                        if (next.getY() < originY) {
                            continue;
                        }
                        if (Math.max(Math.abs(next.getX() - originX),
                                Math.abs(next.getZ() - originZ)) > radius) {
                            continue;
                        }
                        if (!next.getWorld().isChunkLoaded(next.getX() >> 4, next.getZ() >> 4)
                                || next.getType() != logType) {
                            continue;
                        }
                        if (seen.add(next)) {
                            queue.add(next);
                        }
                    }
                }
            }
        }
    }
}
