package com.rpgcore.plugin.data;

import com.rpgcore.plugin.RpgCorePlugin;
import com.rpgcore.plugin.progress.CounterType;
import com.rpgcore.plugin.stats.StatType;
import com.rpgcore.plugin.util.RpgScoreboard;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns the in-memory {@link PlayerData} cache and its write-through to the
 * vanilla scoreboard. Loads on join, flushes only when something changed.
 */
public final class PlayerDataManager {

    private final RpgCorePlugin plugin;
    private final RpgScoreboard board;
    private final Map<UUID, PlayerData> cache = new ConcurrentHashMap<>();
    /** UUIDs whose data was created from scratch, consumed once by the join message. */
    private final Set<UUID> freshlyCreated = ConcurrentHashMap.newKeySet();

    public PlayerDataManager(RpgCorePlugin plugin, RpgScoreboard board) {
        this.plugin = plugin;
        this.board = board;
    }

    /** Creates the mirrored objectives once, at plugin enable. */
    public void createObjectives() {
        board.ensureObjective(RpgScoreboard.LEVEL, "Level");
        board.ensureObjective(RpgScoreboard.XP, "XP");
        board.ensureObjective(RpgScoreboard.XP_NEED, "XP to next level");
        board.ensureObjective(RpgScoreboard.POINTS, "Unspent points");
        board.ensureObjective(RpgScoreboard.WEIGHT, "Weight");
        board.ensureObjective(RpgScoreboard.WEIGHT_MAX, "Max weight");
        board.ensureObjective(RpgScoreboard.WEIGHT_TIER, "Weight tier");
        board.ensureObjective(RpgScoreboard.HP_MAX, "Max HP");
        board.ensureObjective(RpgScoreboard.INITIALISED, "RPGCore initialised");
        board.ensureObjective(RpgScoreboard.GOLD, "Gold");
        for (StatType type : StatType.values()) {
            board.ensureObjective(type.objective(), type.label());
        }
        for (CounterType type : CounterType.values()) {
            if (type.objective() != null) {
                board.ensureObjective(type.objective(), type.label());
            }
        }
    }

    public PlayerData get(Player player) {
        return cache.computeIfAbsent(player.getUniqueId(), uuid -> load(player));
    }

    public PlayerData cached(UUID uuid) {
        return cache.get(uuid);
    }

    public Iterable<PlayerData> all() {
        return cache.values();
    }

    private PlayerData load(Player player) {
        PlayerData data = new PlayerData();

        data.jobId(plugin.jobs().readStored(player));

        if (board.read(player, RpgScoreboard.INITIALISED) != 1) {
            applyFirstJoinDefaults(data);
            data.markInventoryDirty();
            freshlyCreated.add(player.getUniqueId());
            return data;
        }

        data.level(Math.max(1, board.read(player, RpgScoreboard.LEVEL)));
        data.xp(board.read(player, RpgScoreboard.XP));
        data.xpNeed(board.read(player, RpgScoreboard.XP_NEED));
        data.points(board.read(player, RpgScoreboard.POINTS));
        data.gold(board.read(player, RpgScoreboard.GOLD));
        for (StatType type : StatType.values()) {
            data.stat(type, board.read(player, type.objective()));
        }
        for (CounterType type : CounterType.values()) {
            if (type.objective() != null) {
                data.counter(type, board.read(player, type.objective()));
            }
        }
        data.markInventoryDirty();
        return data;
    }

    private void applyFirstJoinDefaults(PlayerData data) {
        data.level(1);
        data.xp(0);
        data.xpNeed(plugin.rpgConfig().xpBase());
        data.points(plugin.rpgConfig().startingPoints());
        data.gold(plugin.rpgConfig().startingGold());
        for (StatType type : StatType.values()) {
            data.stat(type, 0);
        }
    }

    /** Writes changed values back to the scoreboard mirror. */
    public void flush(Player player, PlayerData data) {
        if (!data.dirty()) {
            return;
        }
        board.write(player, RpgScoreboard.INITIALISED, 1);
        board.write(player, RpgScoreboard.LEVEL, data.level());
        board.write(player, RpgScoreboard.XP, data.xp());
        board.write(player, RpgScoreboard.XP_NEED, data.xpNeed());
        board.write(player, RpgScoreboard.POINTS, data.points());
        board.write(player, RpgScoreboard.WEIGHT, data.weight());
        board.write(player, RpgScoreboard.WEIGHT_MAX, data.weightMax());
        board.write(player, RpgScoreboard.WEIGHT_TIER, data.weightTier());
        board.write(player, RpgScoreboard.HP_MAX, data.maxHealth());
        board.write(player, RpgScoreboard.GOLD, data.gold());
        for (StatType type : StatType.values()) {
            board.write(player, type.objective(), data.stat(type));
        }
        for (CounterType type : CounterType.values()) {
            if (type.objective() != null) {
                board.write(player, type.objective(), data.counter(type));
            }
        }
        data.clearDirty();
    }

    /**
     * Adds gold to a player who is not online. The mirror is keyed by name and
     * saved with the world, so this is simply a write - which is what lets a
     * refund reach someone who logged off at the wrong moment.
     */
    public void grantOfflineGold(UUID uuid, int amount) {
        if (amount <= 0) {
            return;
        }
        String name = plugin.getServer().getOfflinePlayer(uuid).getName();
        if (name == null) {
            plugin.getLogger().warning("Could not return " + amount + " gold: no known name for " + uuid + ".");
            return;
        }
        board.write(name, RpgScoreboard.GOLD, board.read(name, RpgScoreboard.GOLD) + amount);
    }

    /** True once, for a player whose data was created on this join. */
    public boolean isFreshlyCreated(Player player) {
        return freshlyCreated.remove(player.getUniqueId());
    }

    public void unload(Player player) {
        PlayerData data = cache.remove(player.getUniqueId());
        if (data != null) {
            data.markDirty();
            flush(player, data);
        }
    }
}
