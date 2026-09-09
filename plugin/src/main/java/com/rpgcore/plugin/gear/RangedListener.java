package com.rpgcore.plugin.gear;

import com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent;
import com.rpgcore.plugin.RpgCorePlugin;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Gear wear for ranged weapons.
 *
 * Melee and mining go through player attributes, but a projectile carries its
 * damage with it and is resolved long after the shot - so the wear has to be
 * baked into the projectile at launch, from the condition of the weapon that
 * actually fired it. That also means swapping to a fresh bow mid-flight does
 * not retroactively strengthen an arrow already in the air, which is the
 * behaviour we want.
 */
public final class RangedListener implements Listener {

    private final RpgCorePlugin plugin;

    public RangedListener(RpgCorePlugin plugin) {
        this.plugin = plugin;
    }

    /** Bows and crossbows. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShootBow(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        applyWear(event.getBow(), event.getProjectile());
    }

    /**
     * Thrown tridents, which do not go through EntityShootBowEvent. Everything
     * else a player launches here (snowballs, pearls, potions) carries no
     * durability, so it is filtered out on the projectile type.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLaunch(PlayerLaunchProjectileEvent event) {
        if (!(event.getProjectile() instanceof Trident)) {
            return;
        }
        applyWear(event.getItemStack(), event.getProjectile());
    }

    private void applyWear(ItemStack weapon, Entity projectile) {
        if (weapon == null || !plugin.rpgConfig().durabilityScalingEnabled()
                || !plugin.rpgConfig().durabilityAffectsRanged()) {
            return;
        }
        if (!(projectile instanceof AbstractArrow arrow)) {
            return;
        }
        int performance = plugin.gear().performancePercent(GearService.conditionPercent(weapon));
        if (performance >= 100) {
            return;
        }
        arrow.setDamage(arrow.getDamage() * performance / 100.0D);
    }
}
