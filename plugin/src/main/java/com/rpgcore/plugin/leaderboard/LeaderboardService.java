package com.rpgcore.plugin.leaderboard;

import com.rpgcore.plugin.RpgCorePlugin;
import com.rpgcore.plugin.progress.CounterType;
import com.rpgcore.plugin.stats.StatType;
import com.rpgcore.plugin.util.RpgScoreboard;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
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
 * Anything mirrored can therefore be ranked for free, which is why gold and
 * every progression tally are categories alongside the stats.
 *
 * Sorting the whole server per command would be wasteful for something players
 * spam, so each category is cached for a few seconds.
 */
public final class LeaderboardService {

    /** One ranked player. */
    public record Row(int rank, String name, int value, int level, int xp) {
    }

    /** What a category ranks on: an objective, and how to name it. */
    private record Category(String id, String objective, String label) {
    }

    private record Cached(List<Row> rows, long expiresAt) {
    }

    private final RpgCorePlugin plugin;
    private final RpgScoreboard board;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    /** Fixed at construction, so listing or validating one costs no allocation. */
    private final Map<String, Category> categories = new LinkedHashMap<>();

    public LeaderboardService(RpgCorePlugin plugin, RpgScoreboard board) {
        this.plugin = plugin;
        this.board = board;

        categories.put("level", new Category("level", RpgScoreboard.LEVEL, "레벨"));
        categories.put("gold", new Category("gold", RpgScoreboard.GOLD, "골드"));
        for (StatType type : StatType.values()) {
            String id = type.name().toLowerCase(Locale.ROOT);
            categories.put(id, new Category(id, type.objective(), type.label()));
        }
        for (CounterType type : CounterType.values()) {
            // LEVEL has no objective of its own and is already listed above.
            if (type.objective() != null) {
                categories.put(type.id(), new Category(type.id(), type.objective(), type.label()));
            }
        }
    }

    public List<String> categories() {
        return List.copyOf(categories.keySet());
    }

    public boolean isCategory(String category) {
        return category != null && categories.containsKey(category.toLowerCase(Locale.ROOT));
    }

    /** The Korean name of a category, for the header and each row. */
    public String label(String category) {
        Category found = categories.get(category == null ? "" : category.toLowerCase(Locale.ROOT));
        return found == null ? category : found.label();
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

        Category ranked = categories.get(key);
        String objective = ranked == null ? RpgScoreboard.LEVEL : ranked.objective();
        List<Row> rows = new ArrayList<>();
        for (String entry : board.entries()) {
            // The init marker is what tells RPGCore's own entries apart from
            // whatever else shares the main scoreboard.
            if (board.read(entry, RpgScoreboard.INITIALISED) != 1) {
                continue;
            }
            int level = board.read(entry, RpgScoreboard.LEVEL);
            int xp = board.read(entry, RpgScoreboard.XP);
            rows.add(new Row(0, entry, board.read(entry, objective), level, xp));
        }

        rows.sort(Comparator.comparingInt(Row::value).reversed()
                .thenComparing(Comparator.comparingInt(Row::level).reversed())
                .thenComparing(Comparator.comparingInt(Row::xp).reversed())
                .thenComparing(Row::name));

        List<Row> numbered = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            numbered.add(new Row(i + 1, row.name(), row.value(), row.level(), row.xp()));
        }

        List<Row> result = List.copyOf(numbered);
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
