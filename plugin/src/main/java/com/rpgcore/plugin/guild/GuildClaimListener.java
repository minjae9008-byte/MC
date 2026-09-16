package com.rpgcore.plugin.guild;

import com.rpgcore.plugin.RpgCorePlugin;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.Iterator;
import java.util.List;

/**
 * What a claimed area actually does.
 *
 * Three jobs, and the ordering between them matters more than any of them
 * individually:
 *
 *   - the banner. Planting one declares the claim and breaking one ends it, so
 *     the block on the ground and the entry in guilds.yml can never disagree.
 *     A refused planting cancels the event, which is what keeps the banner in
 *     the player's hand instead of consuming it for nothing;
 *   - protection. "Outsiders cannot destroy blocks here" has to mean every way
 *     a block is destroyed, not just the one that fires BlockBreakEvent -
 *     creepers, TNT and fire take blocks apart without a player breaking
 *     anything, and a claim that stops the pickaxe but not the creeper is not
 *     protection, it is an inconvenience;
 *   - growth. Crops inside a claim advance further each time they advance,
 *     which rides on the growth event the server already fires rather than
 *     scanning claimed land for farmland on a timer.
 */
public final class GuildClaimListener implements Listener {

    private final RpgCorePlugin plugin;

    public GuildClaimListener(RpgCorePlugin plugin) {
        this.plugin = plugin;
    }

    // ---------------------------------------------------------------- banner

    /**
     * Planting a claim banner. Runs late so a protection plugin that would
     * have refused the placement anyway gets to say so first.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();

        if (isClaimBanner(event.getItemInHand())) {
            if (!plugin.guilds().enabled()) {
                player.sendMessage(ChatColor.RED + "[영지] 이 서버에서는 길드를 쓸 수 없습니다.");
                event.setCancelled(true);
                return;
            }
            // Refused: cancelling puts the banner back rather than eating it.
            if (!plugin.guilds().plantBanner(player, block.getLocation())) {
                event.setCancelled(true);
            }
            return;
        }

        // An ordinary block, placed by somebody who may not be allowed to.
        if (plugin.rpgConfig().guildProtectPlace() && !plugin.guilds().mayBuild(player, block.getLocation())) {
            event.setCancelled(true);
            refuse(player, block.getLocation());
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Player player = event.getPlayer();
        World world = block.getWorld();

        GuildClaim claim = plugin.guilds().claimAt(world, block.getX(), block.getZ());
        if (claim == null) {
            return;
        }
        if (!plugin.guilds().mayBuild(player, block.getLocation())) {
            event.setCancelled(true);
            refuse(player, block.getLocation());
            return;
        }

        if (!claim.isBannerAt(world, block.getX(), block.getY(), block.getZ())) {
            return;
        }

        // The banner is coming down. In a war that settles it - an enemy has
        // won, or the owners have just surrendered by knocking down their own
        // objective - and the flag is a trophy, not a block to pick up.
        if (plugin.guilds().handleBannerBreak(player, claim)) {
            event.setDropItems(false);
            return;
        }

        // Their own flag: the claim ends and the banner goes back in the box,
        // still a claim banner so it can be planted again rather than becoming
        // an ordinary banner the moment it is picked up.
        plugin.guilds().removeBannerAt(world, block.getX(), block.getY(), block.getZ());
        event.setDropItems(false);
        world.dropItemNaturally(block.getLocation().add(0.5D, 0.5D, 0.5D),
                claimBanner(block.getType()));
    }

    /** Lava and water poured over a border destroy blocks without breaking them. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent event) {
        if (!plugin.rpgConfig().guildProtectBuckets()) {
            return;
        }
        Block target = event.getBlockClicked().getRelative(event.getBlockFace());
        if (!plugin.guilds().mayBuild(event.getPlayer(), target.getLocation())) {
            event.setCancelled(true);
            refuse(event.getPlayer(), target.getLocation());
        }
    }

    /**
     * Explosions are filtered block by block rather than cancelled outright:
     * a creeper that blows up on a border should still crater the unclaimed
     * half, and cancelling the whole event would make the claim protect land
     * that is not its own. Land whose guild is mid-war keeps no shield at all -
     * see shieldedFromBlasts.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        handleBlast(event.blockList());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        handleBlast(event.blockList());
    }

    /**
     * Shield first, then deal with whatever is left standing in the list.
     *
     * The sweep for banners runs whether or not explosion protection is on.
     * With it on, a banner on peaceful land has already been removed from the
     * list by the shield, so the sweep finds nothing - but a banner on land
     * whose guild is mid-war has not, because the shield is dropped during a
     * war on purpose. That is the case this exists for: explosives are the
     * obvious way to attack a flag, and before this they destroyed the block
     * while leaving the claim behind and the war unresolved.
     */
    private void handleBlast(List<Block> blocks) {
        if (plugin.rpgConfig().guildProtectExplosions()) {
            shieldClaimed(blocks);
        }
        unclaimDestroyedBanners(blocks);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (!plugin.rpgConfig().guildProtectExplosions()) {
            return;
        }
        Block block = event.getBlock();
        if (plugin.guilds().shieldedFromBlasts(block.getWorld(), block.getX(), block.getZ())) {
            event.setCancelled(true);
        }
    }

    private void shieldClaimed(List<Block> blocks) {
        for (Iterator<Block> it = blocks.iterator(); it.hasNext(); ) {
            Block block = it.next();
            if (plugin.guilds().shieldedFromBlasts(block.getWorld(), block.getX(), block.getZ())) {
                it.remove();
            }
        }
    }

    /**
     * A banner destroyed by something other than a player takes its claim with
     * it - and, if its guild was at war, the war with it. Without this the
     * claim outlives its flag: land still protected, still buffed, with
     * nothing standing on it to say so or to break.
     */
    private void unclaimDestroyedBanners(List<Block> blocks) {
        for (Block block : blocks) {
            if (!block.getType().name().endsWith("BANNER")) {
                continue;
            }
            GuildClaim claim = plugin.guilds().claimAt(block.getWorld(), block.getX(), block.getZ());
            if (claim != null && claim.isBannerAt(block.getWorld(),
                    block.getX(), block.getY(), block.getZ())) {
                plugin.guilds().handleBannerDestroyed(claim);
            }
        }
    }

    // ---------------------------------------------------------------- growth

    /**
     * Claimed land grows faster.
     *
     * Hooked to the growth the server was going to do anyway, so the cost is
     * proportional to the number of crops actually growing rather than to the
     * area claimed - a claim over bare rock costs nothing at all. Each natural
     * step is worth {@code bonus} extra steps, so a bonus of 1 halves the time
     * to harvest.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGrow(BlockGrowEvent event) {
        int bonus = plugin.rpgConfig().guildGrowthBonusStages();
        if (bonus <= 0) {
            return;
        }
        Block block = event.getBlock();
        if (plugin.guilds().claimAt(block.getWorld(), block.getX(), block.getZ()) == null) {
            return;
        }
        // getNewState is where the block is heading; nudging that rather than
        // the block itself keeps this a single change the server applies once.
        // Captured once: getBlockData hands back a copy, so the copy has to be
        // written back to the same state object it came from.
        BlockState newState = event.getNewState();
        BlockData data = newState.getBlockData();
        if (!(data instanceof Ageable ageable) || ageable.getAge() >= ageable.getMaximumAge()) {
            return;
        }
        ageable.setAge(Math.min(ageable.getMaximumAge(), ageable.getAge() + bonus));
        newState.setBlockData(ageable);
    }

    // ---------------------------------------------------------------- shared

    private void refuse(Player player, Location where) {
        String why = plugin.guilds().refusalFor(player, where);
        player.sendActionBar(net.kyori.adventure.text.Component.text(
                ChatColor.RED + (why == null ? "이곳에서는 작업할 수 없습니다." : why)));
    }

    // ------------------------------------------------------------- the item

    private static final String BANNER_KEY = "claim_banner";

    /** True for a banner this plugin handed out as a claim flag. */
    public boolean isClaimBanner(ItemStack stack) {
        if (stack == null || !stack.getType().name().endsWith("BANNER")) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        return meta != null && meta.getPersistentDataContainer()
                .has(new org.bukkit.NamespacedKey(plugin, BANNER_KEY), PersistentDataType.BYTE);
    }

    /**
     * Builds a claim banner.
     *
     * Marked in persistent data rather than by its name, so renaming one at an
     * anvil neither breaks it nor lets a player forge one; the name and lore
     * are there to say what it is, not to identify it.
     */
    public ItemStack claimBanner(Material material) {
        Material banner = itemFormOf(material);
        ItemStack stack = new ItemStack(banner);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + "영지 깃발");
        meta.setLore(List.of(
                ChatColor.GRAY + "세우면 그 자리가 길드 영지가 됩니다.",
                ChatColor.GRAY + "반경 " + plugin.rpgConfig().guildClaimRadius() + "블록.",
                ChatColor.GRAY + "길드장만 세울 수 있고, 부수면 영지가 사라집니다."));
        meta.getPersistentDataContainer().set(
                new org.bukkit.NamespacedKey(plugin, BANNER_KEY), PersistentDataType.BYTE, (byte) 1);
        stack.setItemMeta(meta);
        return stack;
    }

    /**
     * The banner a block turns into when you pick it up.
     *
     * A banner placed against a wall is a *_WALL_BANNER block, and there is no
     * such item - building an ItemStack of one produces something the client
     * cannot render and the server cannot stack. The standing form is the item
     * for both, exactly as it is in vanilla.
     */
    private static Material itemFormOf(Material material) {
        if (material == null || !material.name().endsWith("BANNER")) {
            return Material.WHITE_BANNER;
        }
        if (!material.name().endsWith("_WALL_BANNER")) {
            return material;
        }
        Material standing = Material.matchMaterial(
                material.name().replace("_WALL_BANNER", "_BANNER"));
        return standing == null ? Material.WHITE_BANNER : standing;
    }

    /** Buffs for members standing on their own land; driven by the HUD task. */
    public void applyClaimBuffs(Player player) {
        Location location = player.getLocation();
        GuildClaim claim = plugin.guilds().claimAt(location);
        if (claim == null) {
            return;
        }
        Guild guild = plugin.guilds().byId(claim.guildId());
        if (guild == null || !guild.contains(player.getUniqueId())) {
            return;
        }
        int duration = Math.max(40, plugin.rpgConfig().hudInterval() + 20);
        buff(player, org.bukkit.potion.PotionEffectType.SPEED,
                plugin.rpgConfig().guildBuffSpeed(), duration);
        buff(player, org.bukkit.potion.PotionEffectType.HASTE,
                plugin.rpgConfig().guildBuffHaste(), duration);
        buff(player, org.bukkit.potion.PotionEffectType.JUMP_BOOST,
                plugin.rpgConfig().guildBuffJump(), duration);
    }

    /**
     * Applies one territory buff without stepping on a real one.
     *
     * A player who drank a speed potion has a stronger or longer effect than
     * anything a claim grants, and overwriting it would turn standing on your
     * own land into a downgrade. So the buff only lands when it is actually an
     * improvement on what the player already has.
     */
    private void buff(Player player, org.bukkit.potion.PotionEffectType type, int level, int duration) {
        if (level <= 0) {
            return;
        }
        int amplifier = level - 1;
        org.bukkit.potion.PotionEffect existing = player.getPotionEffect(type);
        if (existing != null
                && (existing.getAmplifier() > amplifier
                    || (existing.getAmplifier() == amplifier && existing.getDuration() > duration))) {
            return;
        }
        player.addPotionEffect(new org.bukkit.potion.PotionEffect(
                type, duration, amplifier, true, false));
    }
}
