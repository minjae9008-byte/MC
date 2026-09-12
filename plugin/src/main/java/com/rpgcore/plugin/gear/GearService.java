package com.rpgcore.plugin.gear;

import com.rpgcore.plugin.RpgCorePlugin;
import com.rpgcore.plugin.config.RpgConfig;
import com.rpgcore.plugin.data.PlayerData;
import com.rpgcore.plugin.util.Attributes;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Gear wear: a damaged weapon hits softer, damaged armour protects less and a
 * damaged tool mines slower.
 *
 * The item itself is never rewritten - that would fight with vanilla repair
 * and with every other plugin. Instead the penalty is a keyed
 * MULTIPLY_SCALAR_1 modifier on the player, recomputed only when the gear can
 * actually have changed (held slot, armour swap, durability loss), exactly
 * like the encumbrance penalty next door.
 *
 * Because the penalty tracks remaining durability, repairing gear - including
 * through the custom anvil recipes - is itself a performance upgrade.
 */
public final class GearService {

    /** Warn about worn gear at most this often, so hotbar flicking is quiet. */
    private static final long WARN_COOLDOWN_MS = 60_000L;

    private final RpgCorePlugin plugin;

    private final NamespacedKey attackKey;
    private final NamespacedKey armorKey;
    private final NamespacedKey toughnessKey;
    private final NamespacedKey miningKey;

    public GearService(RpgCorePlugin plugin) {
        this.plugin = plugin;
        this.attackKey = new NamespacedKey(plugin, "gear_attack");
        this.armorKey = new NamespacedKey(plugin, "gear_armor");
        this.toughnessKey = new NamespacedKey(plugin, "gear_toughness");
        this.miningKey = new NamespacedKey(plugin, "gear_mining");
    }

    /**
     * Remaining durability of a stack as a percentage. Items that cannot take
     * damage (blocks, food, an undamaged tool) are always 100.
     */
    public static int conditionPercent(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return 100;
        }
        ItemMeta meta = stack.getItemMeta();
        if (!(meta instanceof Damageable damageable)) {
            return 100;
        }
        // The max_damage component can override the material's default, so
        // prefer it when the item carries one.
        int max = damageable.hasMaxDamage() ? damageable.getMaxDamage() : stack.getType().getMaxDurability();
        if (max <= 0) {
            return 100;
        }
        int remaining = max - damageable.getDamage();
        return Math.clamp((long) remaining * 100L / max, 0, 100);
    }

    /**
     * Maps remaining durability onto a performance percentage: full above the
     * configured threshold, then a straight line down to the floor at 0.
     */
    public int performancePercent(int conditionPercent) {
        RpgConfig config = plugin.rpgConfig();
        int threshold = config.durabilityFullAbove();
        int floor = config.durabilityMinPerformance();
        if (conditionPercent >= threshold || threshold <= 0) {
            return 100;
        }
        return floor + (100 - floor) * conditionPercent / threshold;
    }

    /**
     * Runs every tick alongside the weight pump; recomputes only the players
     * whose gear a listener flagged, so standing still costs one flag read.
     */
    public void tick() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            PlayerData data = plugin.players().cached(player.getUniqueId());
            if (data != null && data.gearDirty()) {
                recompute(player, data);
            }
        }
    }

    public void recompute(Player player, PlayerData data) {
        data.clearGearDirty();
        RpgConfig config = plugin.rpgConfig();

        if (!config.durabilityScalingEnabled()) {
            data.weaponCondition(100);
            data.armorCondition(100);
            clearAll(player);
            return;
        }

        int handCondition = conditionPercent(player.getInventory().getItemInMainHand());
        int armorCondition = averageArmorCondition(player);
        data.weaponCondition(handCondition);
        data.armorCondition(armorCondition);

        int handPerformance = performancePercent(handCondition);
        int armorPerformance = performancePercent(armorCondition);

        applyPenalty(player, Attributes.attackDamage(), attackKey,
                config.durabilityAffectsAttack() ? handPerformance : 100);
        applyPenalty(player, Attributes.blockBreakSpeed(), miningKey,
                config.durabilityAffectsMining() ? handPerformance : 100);
        applyPenalty(player, Attributes.armor(), armorKey,
                config.durabilityAffectsArmor() ? armorPerformance : 100);
        applyPenalty(player, Attributes.armorToughness(), toughnessKey,
                config.durabilityAffectsArmor() ? armorPerformance : 100);

        maybeWarn(player, data, handPerformance, armorPerformance);
    }

    /**
     * Average condition of the damageable armour actually worn. Empty slots
     * are ignored rather than counted as pristine, so one battered chestplate
     * is not diluted by three empty slots.
     */
    private int averageArmorCondition(Player player) {
        int total = 0;
        int pieces = 0;
        for (ItemStack piece : player.getInventory().getArmorContents()) {
            if (piece == null || piece.getType().isAir()) {
                continue;
            }
            if (!(piece.getItemMeta() instanceof Damageable)) {
                continue;
            }
            total += conditionPercent(piece);
            pieces++;
        }
        return pieces == 0 ? 100 : total / pieces;
    }

    private void applyPenalty(Player player, org.bukkit.attribute.Attribute attribute,
                              NamespacedKey key, int performancePercent) {
        double amount = (performancePercent - 100) / 100.0D;
        Attributes.setModifier(player, attribute, key, amount, AttributeModifier.Operation.MULTIPLY_SCALAR_1);
    }

    /** Public so shutdown can take the wear penalties off; see clearAll. */
    public void clearModifiers(Player player) {
        clearAll(player);
    }

    private void clearAll(Player player) {
        Attributes.removeModifier(player, Attributes.attackDamage(), attackKey);
        Attributes.removeModifier(player, Attributes.blockBreakSpeed(), miningKey);
        Attributes.removeModifier(player, Attributes.armor(), armorKey);
        Attributes.removeModifier(player, Attributes.armorToughness(), toughnessKey);
    }

    private void maybeWarn(Player player, PlayerData data, int handPerformance, int armorPerformance) {
        boolean worn = handPerformance < 100 || armorPerformance < 100;
        if (!worn) {
            data.gearWarned(false);
            return;
        }
        if (data.gearWarned() || System.currentTimeMillis() - data.lastGearWarnMs() < WARN_COOLDOWN_MS) {
            return;
        }
        data.gearWarned(true);
        data.lastGearWarnMs(System.currentTimeMillis());
        player.sendMessage(ChatColor.GRAY + "[RPGCore] 장비가 낡았습니다. "
                + ChatColor.YELLOW + "무기 " + handPerformance + "%"
                + ChatColor.GRAY + " / " + ChatColor.YELLOW + "방어구 " + armorPerformance + "%"
                + ChatColor.GRAY + " 성능. 모루에서 수리하면 회복됩니다.");
    }
}
