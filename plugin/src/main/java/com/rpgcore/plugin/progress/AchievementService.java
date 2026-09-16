package com.rpgcore.plugin.progress;

import com.rpgcore.plugin.RpgCorePlugin;
import com.rpgcore.plugin.config.ConfigFiles;
import com.rpgcore.plugin.data.PlayerData;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Goals and their rewards.
 *
 * The hot path here is {@link #bump}, which every mob killed and every block
 * mined goes through, so it does as little as possible: increment an int in
 * the cached {@link PlayerData}, then walk only the achievements written
 * against that one counter, in goal order, stopping at the first one the
 * player has not reached. Nothing polls and nothing scans every player.
 *
 * "First on the server" goals are settled in records.yml, which is written the
 * moment a claim is made so a crash cannot hand the same first place out twice.
 */
public final class AchievementService {

    public static final String FILE = "achievements.yml";
    private static final String RECORDS_FILE = "records.yml";

    private final RpgCorePlugin plugin;
    private final NamespacedKey earnedKey;
    private final Map<String, Achievement> byId = new LinkedHashMap<>();
    private final Map<CounterType, List<Achievement>> byCounter = new EnumMap<>(CounterType.class);
    /**
     * What each online player has already earned.
     *
     * bump() runs on every mob killed and every block mined, so the set it
     * tests against has to be in memory: re-reading and re-splitting the
     * player's stored string on each swing was the one genuinely hot piece of
     * string work in the plugin.
     */
    private final Map<UUID, Set<String>> earnedCache = new ConcurrentHashMap<>();
    /** Who claimed each first-only goal; persisted so a restart cannot reopen it. */
    private FileConfiguration records;
    private boolean announce;

    public AchievementService(RpgCorePlugin plugin) {
        this.plugin = plugin;
        this.earnedKey = new NamespacedKey(plugin, "achievements");
    }

    public void load() {
        byId.clear();
        byCounter.clear();
        records = ConfigFiles.load(plugin, RECORDS_FILE);

        FileConfiguration config = ConfigFiles.load(plugin, FILE);
        announce = config.getBoolean("announce-to-server", true);
        if (!plugin.rpgConfig().achievementsEnabled()) {
            plugin.getLogger().info("Achievements are disabled (settings.yml features.achievements).");
            return;
        }

        ConfigurationSection list = config.getConfigurationSection("achievements");
        if (list == null) {
            plugin.getLogger().warning(FILE + " has no 'achievements:' section - no goals available.");
            return;
        }
        for (String id : list.getKeys(false)) {
            ConfigurationSection node = list.getConfigurationSection(id);
            if (node != null) {
                parse(id.toLowerCase(Locale.ROOT), node);
            }
        }
        // Goal order is what lets bump() stop at the first unmet goal instead
        // of testing every achievement on the counter each time.
        byCounter.values().forEach(entries -> entries.sort(Comparator.comparingInt(Achievement::goal)));
        plugin.getLogger().info("Achievements loaded: " + byId.size() + ".");
    }

    private void parse(String id, ConfigurationSection node) {
        String context = FILE + " achievements." + id;
        CounterType counter = CounterType.byId(node.getString("type"));
        if (counter == null) {
            plugin.getLogger().warning(context + ": unknown type '" + node.getString("type")
                    + "' - skipped. Valid types: " + validTypes() + ".");
            return;
        }
        int goal = node.getInt("goal", 0);
        if (goal <= 0) {
            plugin.getLogger().warning(context + ": goal must be 1 or more - skipped.");
            return;
        }
        Material icon = Material.matchMaterial(node.getString("icon", "minecraft:paper"));
        if (icon == null) {
            plugin.getLogger().warning(context + ": unknown icon - using paper.");
            icon = Material.PAPER;
        }

        Achievement achievement = new Achievement(id,
                ChatColor.translateAlternateColorCodes('&', node.getString("display", id)),
                ChatColor.translateAlternateColorCodes('&', node.getString("description", "")),
                icon,
                counter,
                goal,
                node.getBoolean("first-only", false),
                node.getString("title"),
                Math.max(0, node.getInt("reward-gold", 0)),
                Math.max(0, node.getInt("reward-xp", 0)));

        byId.put(id, achievement);
        byCounter.computeIfAbsent(counter, k -> new ArrayList<>()).add(achievement);
        if (achievement.grantsTitle()) {
            plugin.titles().register(id, achievement.title(), icon,
                    counter.label() + " " + goal + (achievement.firstOnly() ? " (서버 최초)" : ""));
        }
    }

    private static String validTypes() {
        List<String> names = new ArrayList<>();
        for (CounterType type : CounterType.values()) {
            names.add(type.id());
        }
        return String.join(", ", names);
    }

    /**
     * Adds to a lifetime tally and checks what that may have completed. The
     * whole progression system hangs off this one call.
     */
    public void bump(Player player, CounterType type, int amount) {
        if (amount <= 0 || type == CounterType.LEVEL) {
            return;
        }
        PlayerData data = plugin.players().cached(player.getUniqueId());
        if (data == null) {
            return;
        }
        // Saturating, for the same reason gold saturates: these are ints on
        // the scoreboard mirror, and an overflow would wrap negative, which
        // PlayerData floors at zero - silently resetting a lifetime tally.
        data.counter(type, (int) Math.min((long) data.counter(type) + amount, Integer.MAX_VALUE));
        plugin.players().flush(player, data);
        check(player, data, type);
    }

    /** Levels are not counted, so a level-up asks for its own check. */
    public void checkLevel(Player player, PlayerData data) {
        check(player, data, CounterType.LEVEL);
    }

    private void check(Player player, PlayerData data, CounterType type) {
        List<Achievement> candidates = byCounter.get(type);
        if (candidates == null) {
            return;
        }
        int value = data.counter(type);
        Set<String> earned = earned(player);
        for (Achievement achievement : candidates) {
            if (value < achievement.goal()) {
                // Sorted by goal, so nothing further along can be met either.
                return;
            }
            // The common case by far: a goal passed long ago. Testing the set
            // here keeps the whole path allocation-free once a player is
            // established, which is what a per-block-break hook needs.
            if (!earned.contains(achievement.id())) {
                grant(player, achievement);
            }
        }
    }

    /** Returns false when the player already had it, or lost the race for it. */
    public boolean grant(Player player, Achievement achievement) {
        Set<String> earned = earned(player);
        if (earned.contains(achievement.id())) {
            return false;
        }
        if (achievement.firstOnly() && !claimFirst(player, achievement)) {
            return false;
        }

        earned.add(achievement.id());
        player.getPersistentDataContainer().set(earnedKey, PersistentDataType.STRING,
                String.join(",", earned));

        if (achievement.grantsTitle()) {
            plugin.titles().award(player, achievement.id());
        }
        plugin.economy().give(player, achievement.rewardGold());
        if (achievement.rewardXp() > 0) {
            plugin.stats().addXp(player, achievement.rewardXp());
        }
        announce(player, achievement);
        // After announce(), so a Discord message never describes a title the
        // player did not actually get - claimFirst() above can still refuse.
        plugin.notifier().achievement(player, achievement);
        return true;
    }

    /**
     * Writes the claim before anything else happens, so two players finishing
     * on the same tick cannot both walk away with the "first on the server"
     * title, and a crash mid-reward cannot reopen it.
     */
    private boolean claimFirst(Player player, Achievement achievement) {
        String path = "first." + achievement.id();
        if (records.isSet(path)) {
            return false;
        }
        records.set(path, player.getName());
        records.set("first-at." + achievement.id(), System.currentTimeMillis());
        ConfigFiles.save(plugin, records, RECORDS_FILE);
        return true;
    }

    /** Who took a first-only goal, or null while it is still open. */
    public String firstHolder(Achievement achievement) {
        return records == null ? null : records.getString("first." + achievement.id());
    }

    private void announce(Player player, Achievement achievement) {
        player.sendMessage("");
        player.sendMessage(ChatColor.GOLD + "  ★ " + ChatColor.YELLOW + "업적 달성! "
                + ChatColor.WHITE + achievement.display());
        if (!achievement.description().isBlank()) {
            player.sendMessage(ChatColor.GRAY + "    " + achievement.description());
        }
        List<String> rewards = new ArrayList<>();
        if (achievement.rewardGold() > 0) {
            rewards.add(plugin.economy().format(achievement.rewardGold()));
        }
        if (achievement.rewardXp() > 0) {
            rewards.add(ChatColor.GREEN + "XP " + achievement.rewardXp());
        }
        if (achievement.grantsTitle()) {
            TitleService.Title title = plugin.titles().byId(achievement.id());
            if (title != null) {
                rewards.add(ChatColor.GRAY + "칭호 " + title.display());
            }
        }
        if (!rewards.isEmpty()) {
            player.sendMessage(ChatColor.GRAY + "    보상: " + String.join(ChatColor.GRAY + ", ", rewards));
        }
        player.sendMessage("");
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0F, 1.0F);

        // Server-wide only for the ones worth interrupting everybody for.
        if (announce && (achievement.firstOnly() || achievement.grantsTitle())) {
            String line = ChatColor.YELLOW + "[업적] " + ChatColor.WHITE + player.getName()
                    + ChatColor.YELLOW + " 님이 " + ChatColor.WHITE + achievement.display()
                    + ChatColor.YELLOW + (achievement.firstOnly() ? " 을(를) 서버 최초로 달성했습니다!" : " 을(를) 달성했습니다!");
            for (Player other : plugin.getServer().getOnlinePlayers()) {
                if (!other.equals(player)) {
                    other.sendMessage(line);
                }
            }
        }
    }

    /** The live set for an online player; loaded once and kept in memory. */
    public Set<String> earned(Player player) {
        return earnedCache.computeIfAbsent(player.getUniqueId(), uuid -> read(player));
    }

    /** Reads the stored set on join, so the first bump does not have to. */
    public void load(Player player) {
        earnedCache.put(player.getUniqueId(), read(player));
    }

    public void unload(Player player) {
        earnedCache.remove(player.getUniqueId());
    }

    private Set<String> read(Player player) {
        Set<String> out = new LinkedHashSet<>();
        String raw = player.getPersistentDataContainer().get(earnedKey, PersistentDataType.STRING);
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String part : raw.split(",")) {
            String id = part.trim().toLowerCase(Locale.ROOT);
            if (!id.isEmpty()) {
                out.add(id);
            }
        }
        return out;
    }

    /** Clears a player's progress, for /rpgcore reset. */
    public void reset(Player player, PlayerData data) {
        player.getPersistentDataContainer().remove(earnedKey);
        earnedCache.put(player.getUniqueId(), new LinkedHashSet<>());
        for (CounterType type : CounterType.values()) {
            data.counter(type, 0);
        }
    }

    public List<Achievement> all() {
        return List.copyOf(byId.values());
    }

    public int count() {
        return byId.size();
    }

    public boolean isEmpty() {
        return byId.isEmpty();
    }
}
