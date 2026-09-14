package com.rpgcore.plugin.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;

import java.util.ArrayList;
import java.util.List;

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

    /** One player's row from a bulk scan. */
    public record Row(String entry, int value, int level, int xp) {
    }

    /**
     * Every entry RPGCore has initialised, with one objective's value and the
     * level and XP that break ties.
     *
     * The scan is here rather than in the caller so the four objectives are
     * resolved once for the whole pass instead of once per entry. That matters
     * because the main scoreboard holds an entry for everyone who has ever had
     * a score on this server - RPGCore's players and anybody else's - and the
     * init marker is read first, so an entry that is not ours costs a single
     * lookup rather than four.
     */
    public List<Row> scan(String valueObjective) {
        Scoreboard board = board();
        if (board == null) {
            return List.of();
        }
        Objective initialised = board.getObjective(INITIALISED);
        if (initialised == null) {
            return List.of();
        }
        Objective value = board.getObjective(valueObjective);
        Objective level = board.getObjective(LEVEL);
        Objective xp = board.getObjective(XP);

        List<Row> rows = new ArrayList<>();
        for (String entry : board.getEntries()) {
            Score marker = initialised.getScore(entry);
            if (!marker.isScoreSet() || marker.getScore() != 1) {
                continue;
            }
            rows.add(new Row(entry, scoreOf(value, entry), scoreOf(level, entry), scoreOf(xp, entry)));
        }
        return rows;
    }

    private static int scoreOf(Objective objective, String entry) {
        if (objective == null) {
            return 0;
        }
        Score score = objective.getScore(entry);
        return score.isScoreSet() ? score.getScore() : 0;
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

    /** True when this entry has a score set for the given objective. */
    public boolean hasScore(String entry, String objective) {
        Objective obj = objective(objective);
        return obj != null && obj.getScore(entry).isScoreSet();
    }

    /**
     * Moves the named objectives from one entry name to another, leaving none
     * of them behind under the old one. Objectives not in the list - anyone
     * else's - are left exactly as they were.
     *
     * The mirror is keyed by name because that is what makes it readable with
     * plain /scoreboard, and names are not stable - a player can change theirs,
     * and someone else can then take the old one. Without this, renaming would
     * read as a wiped character and the name's next owner would inherit it.
     */
    public void renameEntry(String from, String to, Iterable<String> objectives) {
        Scoreboard board = board();
        if (board == null || from.equals(to)) {
            return;
        }
        for (String name : objectives) {
            Objective obj = board.getObjective(name);
            if (obj == null) {
                continue;
            }
            Score old = obj.getScore(from);
            if (!old.isScoreSet()) {
                continue;
            }
            obj.getScore(to).setScore(old.getScore());
            // Cleared one objective at a time, not with Scoreboard#resetScores:
            // that clears the entry everywhere, and the main scoreboard is
            // shared - other plugins, datapacks and command blocks key their
            // own objectives by name too. Wiping theirs on the way past is not
            // this plugin's to do; only what it wrote is its to remove.
            old.resetScore();
        }
    }

    private Objective objective(String name) {
        Scoreboard board = board();
        return board == null ? null : board.getObjective(name);
    }

    private Scoreboard board() {
        return Bukkit.getScoreboardManager() == null ? null : Bukkit.getScoreboardManager().getMainScoreboard();
    }
}
