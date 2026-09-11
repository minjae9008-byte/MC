package com.rpgcore.plugin.stats;

import com.rpgcore.plugin.RpgCorePlugin;
import com.rpgcore.plugin.config.RpgConfig;
import com.rpgcore.plugin.data.PlayerData;
import com.rpgcore.plugin.job.RpgJob;
import com.rpgcore.plugin.util.Attributes;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;

import java.util.List;

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

    /**
     * XP earned in play, as opposed to {@link #addXp} which hands a player an
     * exact amount. This is where the job's XP rate and party sharing apply,
     * so the /rpgcore givexp path stays an exact grant.
     */
    public void awardXp(Player earner, int amount) {
        if (amount <= 0) {
            return;
        }
        List<Player> share = plugin.parties().shareTargets(earner);
        if (share.size() <= 1) {
            addXp(earner, scaleForJob(earner, amount));
            return;
        }
        // A party pools its XP and splits it evenly, with a size bonus so
        // grouping up is not a straight loss for the player who earned it.
        double pot = amount * (1.0D + plugin.rpgConfig().partyXpBonusPerMember() * (share.size() - 1));
        int each = Math.max(1, (int) Math.round(pot / share.size()));
        for (Player member : share) {
            addXp(member, scaleForJob(member, each));
        }
    }

    private int scaleForJob(Player player, int amount) {
        RpgJob job = plugin.jobs().of(player);
        if (job == null || job.xpMultiplier() == 1.0D) {
            return amount;
        }
        return Math.max(1, (int) Math.round(amount * job.xpMultiplier()));
    }

    /** A stat as it actually counts: the player's own points plus the job's. */
    public int effectiveStat(PlayerData data, StatType type) {
        RpgJob job = plugin.jobs().byId(data.jobId());
        return data.stat(type) + (job == null ? 0 : job.statBonus(type));
    }

    public void addXp(Player player, int amount) {
        if (amount <= 0) {
            return;
        }
        PlayerData data = plugin.players().get(player);
        if (atMaxLevel(data)) {
            // Capped: XP is dropped rather than banked, so lifting the cap
            // later does not hand everyone a pile of instant levels.
            announceMaxLevel(player, data);
            return;
        }
        data.xp(data.xp() + amount);

        boolean levelled = false;
        while (data.xp() >= data.xpNeed() && data.xpNeed() > 0 && !atMaxLevel(data)) {
            data.xp(data.xp() - data.xpNeed());
            data.level(data.level() + 1);
            data.points(data.points() + plugin.rpgConfig().pointsPerLevel());
            data.xpNeed(xpNeedFor(data.level()));
            levelled = true;
        }
        if (atMaxLevel(data)) {
            data.xp(0);
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

    /**
     * XP needed to leave {@code level}:
     *   (xp-base + (level-1) * xp-growth) * xp-multiplier^(level-1)
     *
     * A multiplier of 1.0 leaves the straight line the plugin always had, and
     * anything above it bends the curve upward. Clamped because the
     * exponential overflows an int quickly at high multipliers.
     */
    public int xpNeedFor(int level) {
        RpgConfig config = plugin.rpgConfig();
        int steps = Math.max(0, level - 1);
        double linear = config.xpBase() + (double) steps * config.xpGrowth();
        double curved = linear * Math.pow(config.xpMultiplier(), steps);
        return (int) Math.clamp(Math.round(curved), 1L, (long) Integer.MAX_VALUE);
    }

    /** True when the player cannot level any further. */
    public boolean atMaxLevel(PlayerData data) {
        int max = plugin.rpgConfig().maxLevel();
        return max > 0 && data.level() >= max;
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
                + (long) effectiveStat(data, StatType.VIT) * config.hpPerVit(), 1, MAX_ATTRIBUTE_HEALTH);
        data.maxHealth(maxHealth);
        Attributes.setBase(player, Attributes.maxHealth(), maxHealth);
        if (player.getHealth() > maxHealth) {
            player.setHealth(maxHealth);
        }

        Attributes.setModifier(player, Attributes.attackDamage(), strDamageKey,
                effectiveStat(data, StatType.STR) * config.attackPerStr(), AttributeModifier.Operation.ADD_NUMBER);
        Attributes.setModifier(player, Attributes.attackSpeed(), dexAttackSpeedKey,
                effectiveStat(data, StatType.DEX) * config.attackSpeedPerDex(), AttributeModifier.Operation.ADD_NUMBER);
        Attributes.setModifier(player, Attributes.movementSpeed(), agiSpeedKey,
                effectiveStat(data, StatType.AGI) * config.speedPerAgi(), AttributeModifier.Operation.ADD_NUMBER);
        Attributes.setModifier(player, Attributes.jumpStrength(), agiJumpKey,
                effectiveStat(data, StatType.AGI) * config.jumpPerAgi(), AttributeModifier.Operation.ADD_NUMBER);
        Attributes.setModifier(player, Attributes.luck(), luckKey,
                effectiveStat(data, StatType.LUCK) * config.luckPerLuck(), AttributeModifier.Operation.ADD_NUMBER);

        applyJobAttributes(player, data);

        data.weightMax(config.weightBase()
                + effectiveStat(data, StatType.STR) * config.weightPerStr()
                + jobWeightBonus(data));
        // Capacity changed, so the encumbrance tier may have changed with it;
        // and this runs on join/respawn/reload, where the gear penalty also
        // needs re-applying.
        data.markInventoryDirty();
    }

    private int jobWeightBonus(PlayerData data) {
        RpgJob job = plugin.jobs().byId(data.jobId());
        return job == null ? 0 : job.weightBonus();
    }

    /**
     * Walks every attribute any configured job touches, not just the ones the
     * current job uses, so switching away from a job actually takes its bonus
     * off. A zero amount removes the modifier.
     */
    private void applyJobAttributes(Player player, PlayerData data) {
        RpgJob job = plugin.jobs().byId(data.jobId());
        for (String id : plugin.jobs().managedAttributes()) {
            Attribute attribute = Attributes.byId(id);
            if (attribute == null) {
                continue;
            }
            double add = job == null ? 0.0D : job.attributeAdd().getOrDefault(id, 0.0D);
            double mul = job == null ? 0.0D : job.attributeMul().getOrDefault(id, 0.0D);
            Attributes.setModifier(player, attribute, jobKey("add", id), add,
                    AttributeModifier.Operation.ADD_NUMBER);
            Attributes.setModifier(player, attribute, jobKey("mul", id), mul,
                    AttributeModifier.Operation.MULTIPLY_SCALAR_1);
        }
    }

    private NamespacedKey jobKey(String kind, String attributeId) {
        return new NamespacedKey(plugin, "job_" + kind + "_" + attributeId.replace(':', '.'));
    }

    /** Told once per minute at most, so a capped player is not spammed. */
    private void announceMaxLevel(Player player, PlayerData data) {
        long now = System.currentTimeMillis();
        if (now - data.lastMaxLevelNoticeMs() < 60_000L) {
            return;
        }
        data.lastMaxLevelNoticeMs(now);
        player.sendMessage(ChatColor.GOLD + "[RPGCore] 최대 레벨 " + data.level() + " 에 도달했습니다.");
    }

    private void announceLevelUp(Player player, PlayerData data) {
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0F, 1.0F);
        player.sendMessage(ChatColor.GOLD + "[RPGCore] " + ChatColor.YELLOW + "LEVEL UP! Lv." + data.level()
                + (atMaxLevel(data) ? ChatColor.GOLD + " (최대)" : "")
                + ChatColor.AQUA + "  (스탯 포인트 " + data.points() + "개 보유)");
    }
}
