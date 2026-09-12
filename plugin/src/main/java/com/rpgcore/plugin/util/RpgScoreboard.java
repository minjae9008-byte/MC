package com.rpgcore.plugin.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;

import java.util.Set;

/**
 * Mirror of the plugin's state onto the vanilla scoreboard.
 *
 * The plugin is the engine - hot paths read {@link com.rpgcore.plugin.data.PlayerData}
 * from memory, never this. The mirror exists for two reasons that are worth its
 * (small, write-on-change-only) cost:
 *   1. persistence for free - the vanilla scoreboard is saved with the world,
 *      so there is no extra data file to manage or corrupt;
 *   2. interop - admins can `/scoreboard players get`, and other datapacks or
 *      command blocks can read RPG values without touching this plugin.
 */
public final class RpgScoreboard {

    public static final String LEVEL = "rpgcore.level";
    public static final String XP = "rpgcore.xp";
    public static final String XP_NEED = "rpgcore.xp_need";
    public static final String POINTS = "rpgcore.points";
    public static final String WEIGHT = "rpgcore.weight";
    public static final String WEIGHT_MAX = "rpgcore.weight_max";
    public static final String WEIGHT_TIER = "rpgcore.weight_tier";
    public static final String HP_MAX = "rpgcore.hp_max";
    public static final String GOLD = "rpgcore.gold";
    public static final String INITIALISED = "rpgcore.init";

    @SuppressWarnings("deprecation") // String-criteria overload is the version-portable one
    public void ensureObjective(String name, String displayName) {
        Scoreboard board = board();
        if (board == null || board.getObjective(name) != null) {
            return;
        }
        board.registerNewObjective(name, "dummy", displayName);
    }

    public int read(Player player, String objective) {
        return read(player.getName(), objective);
    }

    /**
     * By entry name rather than by Player, which is what lets the leaderboard
     * rank people who are not online - the mirror keeps their scores.
     */
    public int read(String entry, String objective) {
        Objective obj = objective(objective);
        if (obj == null) {
            return 0;
        }
        Score score = obj.getScore(entry);
        return score.isScoreSet() ? score.getScore() : 0;
    }

    /** Every entry the main scoreboard tracks, RPGCore's and otherwise. */
    public Set<String> entries() {
        Scoreboard board = board();
        return board == null ? Set.of() : board.getEntries();
    }

    public void write(Player player, String objective, int value) {
        write(player.getName(), objective, value);
    }

    /** By entry name, the write that pairs with the offline-capable read. */
    public void write(String entry, String objective, int value) {
        Objective obj = objective(objective);
        if (obj == null) {
            return;
        }
        obj.getScore(entry).setScore(value);
    }

    private Objective objective(String name) {
        Scoreboard board = board();
        return board == null ? null : board.getObjective(name);
    }

    private Scoreboard board() {
        return Bukkit.getScoreboardManager() == null ? null : Bukkit.getScoreboardManager().getMainScoreboard();
    }
}
