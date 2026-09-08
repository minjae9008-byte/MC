package com.rpgcore.plugin.weight;

import com.rpgcore.plugin.RpgCorePlugin;
import com.rpgcore.plugin.data.PlayerData;
import com.rpgcore.plugin.util.Attributes;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Encumbrance.
 *
 * The datapack version rescanned everyone every 10 ticks with 164 conditional
 * commands each. Here a scan is a 41-slot array walk with an EnumMap lookup,
 * and it only happens for players whose inventory actually changed (events set
 * a dirty flag), with a low-frequency safety rescan as a backstop. Attribute
 * modifiers are only touched when the tier changes, so a player standing still
 * with a full inventory costs nothing at all.
 */
public final class WeightService {

    private final RpgCorePlugin plugin;
    private final ItemWeightTable table;

    private final NamespacedKey speedPenaltyKey;
    private final NamespacedKey jumpPenaltyKey;

    private int rescanCounter;

    public WeightService(RpgCorePlugin plugin, ItemWeightTable table) {
        this.plugin = plugin;
        this.table = table;
        this.speedPenaltyKey = new NamespacedKey(plugin, "weight_speed");
        this.jumpPenaltyKey = new NamespacedKey(plugin, "weight_jump");
    }

    /** Runs every tick; processes only dirty players, capped per tick. */
    public void tick() {
        int budget = plugin.rpgConfig().weightScanBatch();

        if (++rescanCounter >= plugin.rpgConfig().weightRescanInterval()) {
            rescanCounter = 0;
            for (PlayerData data : plugin.players().all()) {
                // Both flags: this is the backstop for inventory changes that
                // fire no event at all, such as /give or another plugin
                // writing straight into the inventory, and those move gear
                // just as easily as they move weight.
                data.markInventoryDirty();
            }
        }

        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (budget <= 0) {
                return;
            }
            PlayerData data = plugin.players().cached(player.getUniqueId());
            if (data == null || !data.weightDirty()) {
                continue;
            }
            budget--;
            recompute(player, data);
        }
    }

    public void recompute(Player player, PlayerData data) {
        data.clearWeightDirty();

        int total = 0;
        // PlayerInventory#getContents covers the 36 main slots plus armour and
        // offhand, so one call is the whole carried load.
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack == null || stack.getType().isAir()) {
                continue;
            }
            total += table.weightOf(stack.getType()) * stack.getAmount();
        }

        data.weight(total);

        int previousTier = data.weightTier();
        int tier = tierFor(data.loadPercent());
        // The modifiers are also (re)applied on the first recompute of a
        // session even when the tier has not moved: they survive in the
        // player's saved attributes, so a player who logged out encumbered
        // would otherwise keep the penalty after dropping the load.
        if (tier != previousTier || !data.tierApplied()) {
            data.weightTier(tier);
            data.markTierApplied();
            applyTier(player, tier);
            if (tier != previousTier) {
                notifyTierChange(player, tier);
            }
        }
        plugin.players().flush(player, data);
    }

    private int tierFor(int loadPercent) {
        if (loadPercent < 70) {
            return 0;
        }
        if (loadPercent < 100) {
            return 1;
        }
        if (loadPercent < 130) {
            return 2;
        }
        return 3;
    }

    /**
     * Tier penalties. Bedrock (Geyser) has no player jump-strength attribute,
     * so the jump penalty is Java-only; the speed penalty and the hunger drain
     * apply on both platforms.
     */
    private void applyTier(Player player, int tier) {
        double speed = switch (tier) {
            case 1 -> -0.10D;
            case 2 -> -0.25D;
            case 3 -> -0.50D;
            default -> 0.0D;
        };
        double jump = switch (tier) {
            case 1 -> -0.15D;
            case 2 -> -0.40D;
            case 3 -> -0.70D;
            default -> 0.0D;
        };

        Attributes.setModifier(player, Attributes.movementSpeed(), speedPenaltyKey,
                speed, AttributeModifier.Operation.MULTIPLY_SCALAR_1);
        Attributes.setModifier(player, Attributes.jumpStrength(), jumpPenaltyKey,
                jump, AttributeModifier.Operation.MULTIPLY_SCALAR_1);
    }

    /** Called every second by the HUD task, only while overloaded. */
    public void applyOverloadEffects(Player player, PlayerData data) {
        switch (data.weightTier()) {
            case 2 -> player.addPotionEffect(new PotionEffect(PotionEffectType.HUNGER, 40, 0, true, false));
            case 3 -> {
                player.addPotionEffect(new PotionEffect(PotionEffectType.HUNGER, 40, 1, true, false));
                player.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 40, 0, true, false));
            }
            default -> {
            }
        }
    }

    private void notifyTierChange(Player player, int tier) {
        if (tier == 0) {
            player.sendMessage(ChatColor.GREEN + "[RPGCore] 짐이 가벼워졌습니다. 페널티가 해제되었습니다.");
            return;
        }
        // Every move between loaded tiers is announced, in both directions -
        // dropping from 3 to 1 lifts real penalties and the player should see
        // which ones they still have.
        String message = switch (tier) {
            case 1 -> ChatColor.YELLOW + "[RPGCore] 짐 무게: 1단계. (이동속도 -10%)";
            case 2 -> ChatColor.GOLD + "[RPGCore] 과적재 2단계! 이동속도 -25%, 허기가 빨리 닳습니다.";
            case 3 -> ChatColor.RED + "[RPGCore] 과적재 3단계! 이동속도 -50%, 채굴 속도도 느려집니다.";
            default -> null;
        };
        if (message != null) {
            player.sendMessage(message);
        }
    }
}
