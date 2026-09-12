package com.rpgcore.plugin.data;

import com.rpgcore.plugin.RpgCorePlugin;
import com.rpgcore.plugin.progress.CounterType;
import com.rpgcore.plugin.stats.StatType;
import com.rpgcore.plugin.util.RpgScoreboard;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns the in-memory {@link PlayerData} cache and its write-through to the
 * vanilla scoreboard. Loads on join, flushes only when something changed.
 *
 * The mirror is keyed by player name, which is what lets an operator read and
 * write RPG values with plain /scoreboard - but names move between accounts,
 * so the name a player had last time is remembered on the player themselves
 * (by UUID) and their entry is carried over when it changes. See
 * {@link #migrateRename}.
 */
public final class PlayerDataManager {

    private final RpgCorePlugin plugin;
    private final RpgScoreboard board;
    /** Last name this player's mirror entry was written under, keyed by UUID. */
    private final NamespacedKey nameKey;
    private final Map<UUID, PlayerData> cache = new ConcurrentHashMap<>();
    /** UUIDs whose data was created from scratch, consumed once by the join message. */
    private final Set<UUID> freshlyCreated = ConcurrentHashMap.newKeySet();

    public PlayerDataManager(RpgCorePlugin plugin, RpgScoreboard board) {
        this.plugin = plugin;
        this.board = board;
        this.nameKey = new NamespacedKey(plugin, "mirror_name");
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

        // Before a single value is read: the entry this player's scores live
        // under may be filed under the name they used to have.
        migrateRename(player);

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

    /**
     * Carries a player's mirror entry over when their name has changed, and
     * records the name it now lives under.
     *
     * Everything on the scoreboard side - level, XP, gold, stats, counters -
     * is keyed by name; everything on the persistent-data side - job,
     * achievements, titles, the collection log - is keyed by UUID. Without
     * this the two halves come apart on a rename: the player keeps their
     * achievements but comes back at level 1 with no gold, and whoever
     * registers their old name inherits what they left behind. The stored name
     * is the UUID-keyed anchor that keeps the name-keyed half attached to the
     * right account.
     */
    private void migrateRename(Player player) {
        String current = player.getName();
        String previous = player.getPersistentDataContainer().get(nameKey, PersistentDataType.STRING);
        if (current.equals(previous)) {
            return;
        }
        if (previous != null && board.hasScore(previous, RpgScoreboard.INITIALISED)) {
            board.renameEntry(previous, current, mirroredObjectives());
            plugin.getLogger().info("Moved RPGCore scores from '" + previous + "' to '"
                    + current + "' (" + player.getUniqueId() + ") after a name change.");
        }
        player.getPersistentDataContainer().set(nameKey, PersistentDataType.STRING, current);
    }

    /** Every objective this plugin mirrors, in no particular order. */
    private List<String> mirroredObjectives() {
        List<String> names = new ArrayList<>(List.of(
                RpgScoreboard.INITIALISED, RpgScoreboard.LEVEL, RpgScoreboard.XP,
                RpgScoreboard.XP_NEED, RpgScoreboard.POINTS, RpgScoreboard.WEIGHT,
                RpgScoreboard.WEIGHT_MAX, RpgScoreboard.WEIGHT_TIER, RpgScoreboard.HP_MAX,
                RpgScoreboard.GOLD));
        for (StatType type : StatType.values()) {
            names.add(type.objective());
        }
        for (CounterType type : CounterType.values()) {
            if (type.objective() != null) {
                names.add(type.objective());
            }
        }
        return names;
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
        write(player, data, RpgScoreboard.INITIALISED, 1);
        write(player, data, RpgScoreboard.LEVEL, data.level());
        write(player, data, RpgScoreboard.XP, data.xp());
        write(player, data, RpgScoreboard.XP_NEED, data.xpNeed());
        write(player, data, RpgScoreboard.POINTS, data.points());
        write(player, data, RpgScoreboard.WEIGHT, data.weight());
        write(player, data, RpgScoreboard.WEIGHT_MAX, data.weightMax());
        write(player, data, RpgScoreboard.WEIGHT_TIER, data.weightTier());
        write(player, data, RpgScoreboard.HP_MAX, data.maxHealth());
        write(player, data, RpgScoreboard.GOLD, data.gold());
        for (StatType type : StatType.values()) {
            write(player, data, type.objective(), data.stat(type));
        }
        for (CounterType type : CounterType.values()) {
            if (type.objective() != null) {
                write(player, data, type.objective(), data.counter(type));
            }
        }
        data.clearDirty();
    }

    /** One objective, written only when its value actually moved. */
    private void write(Player player, PlayerData data, String objective, int value) {
        if (data.mirrorChanged(objective, value)) {
            board.write(player, objective, value);
        }
    }

    /** True once, for a player whose data was created on this join. */
    public boolean isFreshlyCreated(Player player) {
        return freshlyCreated.remove(player.getUniqueId());
    }

    public void unload(Player player) {
        PlayerData data = cache.remove(player.getUniqueId());
        if (data != null) {
            // A full write on the way out: during the session a flush skips
            // values that have not moved, so this is what puts the mirror back
            // in step if something else wrote to the scoreboard meanwhile.
            data.forgetMirror();
            data.markDirty();
            flush(player, data);
        }
    }
}
