package com.rpgcore.plugin.stats;

import com.rpgcore.plugin.RpgCorePlugin;
import com.rpgcore.plugin.config.RpgConfig;
import com.rpgcore.plugin.data.PlayerData;
import com.rpgcore.plugin.util.Attributes;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;

/**
 * Owns levelling, stat allocation and every derived value. Nothing here runs
 * on a timer - it is called from the events that can actually change a stat.
 */
public final class StatsService {

    /** Vanilla's hard ceiling for the max_health attribute base value. */
    private static final int MAX_ATTRIBUTE_HEALTH = 1024;

    private final RpgCorePlugin plugin;

    private final NamespacedKey strDamageKey;
    private final NamespacedKey dexAttackSpeedKey;
    private final NamespacedKey agiSpeedKey;
    private final NamespacedKey agiJumpKey;
    private final NamespacedKey luckKey;

    public StatsService(RpgCorePlugin plugin) {
        this.plugin = plugin;
        this.strDamageKey = new NamespacedKey(plugin, "str_damage");
        this.dexAttackSpeedKey = new NamespacedKey(plugin, "dex_attack_speed");
        this.agiSpeedKey = new NamespacedKey(plugin, "agi_speed");
        this.agiJumpKey = new NamespacedKey(plugin, "agi_jump");
        this.luckKey = new NamespacedKey(plugin, "luck_bonus");
    }

    public void addXp(Player player, int amount) {
        if (amount <= 0) {
            return;
        }
        PlayerData data = plugin.players().get(player);
        data.xp(data.xp() + amount);

        boolean levelled = false;
        while (data.xp() >= data.xpNeed() && data.xpNeed() > 0) {
            data.xp(data.xp() - data.xpNeed());
            data.level(data.level() + 1);
            data.points(data.points() + plugin.rpgConfig().pointsPerLevel());
            data.xpNeed(xpNeedFor(data.level()));
            levelled = true;
        }

        if (levelled) {
            recalculate(player, data);
            announceLevelUp(player, data);
        }
        plugin.players().flush(player, data);
    }

    public boolean allocate(Player player, StatType type) {
        PlayerData data = plugin.players().get(player);
        if (data.points() <= 0) {
            player.sendMessage(ChatColor.RED + "[RPGCore] 사용 가능한 스탯 포인트가 없습니다.");
            return false;
        }
        data.points(data.points() - 1);
        data.stat(type, data.stat(type) + 1);
        recalculate(player, data);
        plugin.players().flush(player, data);

        player.sendMessage(ChatColor.GOLD + "[RPGCore] " + ChatColor.YELLOW + type.label() + " +1 -> "
                + ChatColor.AQUA + data.stat(type)
                + ChatColor.GRAY + " (남은 포인트 " + data.points() + ")");
        return true;
    }

    public int xpNeedFor(int level) {
        RpgConfig config = plugin.rpgConfig();
        return config.xpBase() + Math.max(0, level - 1) * config.xpGrowth();
    }

    public void recalculate(Player player) {
        recalculate(player, plugin.players().get(player));
    }

    /**
     * Recomputes every derived value from the base stats. Add a new stat's
     * effect here - this is the only place that maps stats onto attributes.
     */
    public void recalculate(Player player, PlayerData data) {
        RpgConfig config = plugin.rpgConfig();

        if (data.xpNeed() <= 0) {
            data.xpNeed(xpNeedFor(data.level()));
        }

        // Vanilla rejects a max_health base outside (0, 1024], and a high
        // enough level or VIT would otherwise sail past that and make every
        // recalculate() throw.
        int maxHealth = Math.clamp((long) config.baseHp()
                + (long) data.level() * config.hpPerLevel()
                + (long) data.stat(StatType.VIT) * config.hpPerVit(), 1, MAX_ATTRIBUTE_HEALTH);
        data.maxHealth(maxHealth);
        Attributes.setBase(player, Attributes.maxHealth(), maxHealth);
        if (player.getHealth() > maxHealth) {
            player.setHealth(maxHealth);
        }

        Attributes.setModifier(player, Attributes.attackDamage(), strDamageKey,
                data.stat(StatType.STR) * config.attackPerStr(), AttributeModifier.Operation.ADD_NUMBER);
        Attributes.setModifier(player, Attributes.attackSpeed(), dexAttackSpeedKey,
                data.stat(StatType.DEX) * config.attackSpeedPerDex(), AttributeModifier.Operation.ADD_NUMBER);
        Attributes.setModifier(player, Attributes.movementSpeed(), agiSpeedKey,
                data.stat(StatType.AGI) * config.speedPerAgi(), AttributeModifier.Operation.ADD_NUMBER);
        Attributes.setModifier(player, Attributes.jumpStrength(), agiJumpKey,
                data.stat(StatType.AGI) * config.jumpPerAgi(), AttributeModifier.Operation.ADD_NUMBER);
        Attributes.setModifier(player, Attributes.luck(), luckKey,
                data.stat(StatType.LUCK) * config.luckPerLuck(), AttributeModifier.Operation.ADD_NUMBER);

        data.weightMax(config.weightBase() + data.stat(StatType.STR) * config.weightPerStr());
        // Capacity changed, so the encumbrance tier may have changed with it;
        // and this runs on join/respawn/reload, where the gear penalty also
        // needs re-applying.
        data.markInventoryDirty();
    }

    private void announceLevelUp(Player player, PlayerData data) {
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0F, 1.0F);
        player.sendMessage(ChatColor.GOLD + "[RPGCore] " + ChatColor.YELLOW + "LEVEL UP! Lv." + data.level()
                + ChatColor.AQUA + "  (스탯 포인트 " + data.points() + "개 보유)");
    }
}
