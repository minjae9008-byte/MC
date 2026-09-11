package com.rpgcore.plugin.leaderboard;

import com.rpgcore.plugin.RpgCorePlugin;
import com.rpgcore.plugin.stats.StatType;
import com.rpgcore.plugin.util.RpgScoreboard;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rankings, read straight out of the scoreboard mirror.
 *
 * That mirror is written for every player who has ever joined and is saved
 * with the world, so it already holds offline players - no extra storage, and
 * the leaderboard is right even for someone who has not logged in for months.
 *
 * Sorting the whole server per command would be wasteful for something players
 * spam, so each category is cached for a few seconds.
 */
public final class LeaderboardService {

    /** One ranked player. */
    public record Row(int rank, String name, int value, int level, int xp) {
    }

    private record Cached(List<Row> rows, long expiresAt) {
    }

    private final RpgCorePlugin plugin;
    private final RpgScoreboard board;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public LeaderboardService(RpgCorePlugin plugin, RpgScoreboard board) {
        this.plugin = plugin;
        this.board = board;
    }

    /** Category ids accepted by /leaderboard: "level" plus every stat. */
    public List<String> categories() {
        List<String> out = new ArrayList<>();
        out.add("level");
        for (StatType type : StatType.values()) {
            out.add(type.name().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    public boolean isCategory(String category) {
        return categories().contains(category.toLowerCase(Locale.ROOT));
    }

    public void invalidate() {
        cache.clear();
    }

    /** The full ranking for a category, freshest within the cache window. */
    public List<Row> ranking(String category) {
        String key = category.toLowerCase(Locale.ROOT);
        Cached cached = cache.get(key);
        long now = System.currentTimeMillis();
        if (cached != null && cached.expiresAt() > now) {
            return cached.rows();
        }

        StatType stat = StatType.byName(key);
        List<Row> rows = new ArrayList<>();
        for (String entry : board.entries()) {
            // The init marker is what tells RPGCore's own entries apart from
            // whatever else shares the main scoreboard.
            if (board.read(entry, RpgScoreboard.INITIALISED) != 1) {
                continue;
            }
            int level = board.read(entry, RpgScoreboard.LEVEL);
            int xp = board.read(entry, RpgScoreboard.XP);
            int value = stat == null ? level : board.read(entry, stat.objective());
            rows.add(new Row(0, entry, value, level, xp));
        }

        rows.sort(Comparator.comparingInt(Row::value).reversed()
                .thenComparing(Comparator.comparingInt(Row::level).reversed())
                .thenComparing(Comparator.comparingInt(Row::xp).reversed())
                .thenComparing(Row::name));

        List<Row> ranked = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            ranked.add(new Row(i + 1, row.name(), row.value(), row.level(), row.xp()));
        }

        List<Row> result = List.copyOf(ranked);
        cache.put(key, new Cached(result, now + plugin.rpgConfig().leaderboardCacheSeconds() * 1000L));
        return result;
    }

    /** That player's row, or null when they have no RPGCore data yet. */
    public Row rankOf(String playerName, String category) {
        for (Row row : ranking(category)) {
            if (row.name().equalsIgnoreCase(playerName)) {
                return row;
            }
        }
        return null;
    }
}
