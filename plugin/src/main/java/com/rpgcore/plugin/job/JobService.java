package com.rpgcore.plugin.job;

import com.rpgcore.plugin.RpgCorePlugin;
import com.rpgcore.plugin.data.PlayerData;
import com.rpgcore.plugin.stats.StatType;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Jobs (classes): loading them from config, remembering which one a player
 * picked, and turning that choice into attribute modifiers.
 *
 * The choice lives in the player's own persistent data container, which is
 * saved inside their vanilla playerdata file. That keeps the plugin's promise
 * of no separate data files while still storing something the integer-only
 * scoreboard cannot hold.
 */
public final class JobService {

    private final RpgCorePlugin plugin;
    private final NamespacedKey storageKey;
    private final Map<String, RpgJob> jobs = new LinkedHashMap<>();
    /**
     * Every attribute any job touches. A job change has to clear the modifiers
     * of the job left behind, so recalculate walks this whole set rather than
     * just the attributes of the job now worn.
     */
    private final List<String> managedAttributes = new ArrayList<>();

    public JobService(RpgCorePlugin plugin) {
        this.plugin = plugin;
        this.storageKey = new NamespacedKey(plugin, "job");
    }

    public void load() {
        jobs.clear();
        managedAttributes.clear();
        if (!plugin.getConfig().getBoolean("jobs.enabled", true)) {
            plugin.getLogger().info("Jobs are disabled.");
            return;
        }
        ConfigurationSection list = plugin.getConfig().getConfigurationSection("jobs.list");
        if (list == null) {
            plugin.getLogger().warning("jobs.list missing from config.yml - no jobs available.");
            return;
        }

        TreeSet<String> touched = new TreeSet<>();
        for (String id : list.getKeys(false)) {
            ConfigurationSection node = list.getConfigurationSection(id);
            if (node == null) {
                continue;
            }
            RpgJob job = parse(id.toLowerCase(Locale.ROOT), node);
            if (job != null) {
                jobs.put(job.id(), job);
                touched.addAll(job.attributeAdd().keySet());
                touched.addAll(job.attributeMul().keySet());
            }
        }
        managedAttributes.addAll(touched);
        plugin.getLogger().info("Jobs loaded: " + jobs.size() + ".");
    }

    private RpgJob parse(String id, ConfigurationSection node) {
        String context = "jobs.list." + id;

        Material icon = Material.matchMaterial(node.getString("icon", "minecraft:paper"));
        if (icon == null) {
            plugin.getLogger().warning(context + ": unknown icon - using paper.");
            icon = Material.PAPER;
        }

        Map<StatType, Integer> statBonus = new EnumMap<>(StatType.class);
        ConfigurationSection stats = node.getConfigurationSection("stat-bonus");
        if (stats != null) {
            for (String key : stats.getKeys(false)) {
                StatType type = StatType.byName(key);
                if (type == null) {
                    plugin.getLogger().warning(context + ": unknown stat " + key + " - skipped.");
                    continue;
                }
                statBonus.put(type, stats.getInt(key));
            }
        }

        List<String> description = new ArrayList<>();
        for (String line : node.getStringList("description")) {
            description.add(ChatColor.translateAlternateColorCodes('&', line));
        }

        return new RpgJob(id,
                ChatColor.translateAlternateColorCodes('&', node.getString("name", id)),
                icon,
                List.copyOf(description),
                Math.max(1, node.getInt("min-level", 1)),
                Map.copyOf(statBonus),
                node.getInt("weight-bonus", 0),
                node.getDouble("xp-multiplier", 1.0D),
                readAttributes(node, "attributes.add", context),
                readAttributes(node, "attributes.multiply", context));
    }

    private Map<String, Double> readAttributes(ConfigurationSection node, String path, String context) {
        ConfigurationSection section = node.getConfigurationSection(path);
        if (section == null) {
            return Map.of();
        }
        Map<String, Double> out = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            if (com.rpgcore.plugin.util.Attributes.byId(key) == null) {
                plugin.getLogger().warning(context + "." + path + ": unknown attribute " + key + " - skipped.");
                continue;
            }
            out.put(key, section.getDouble(key));
        }
        return Map.copyOf(out);
    }

    public boolean isEmpty() {
        return jobs.isEmpty();
    }

    public List<RpgJob> jobs() {
        return List.copyOf(jobs.values());
    }

    public List<String> managedAttributes() {
        return managedAttributes;
    }

    public RpgJob byId(String id) {
        return id == null ? null : jobs.get(id.toLowerCase(Locale.ROOT));
    }

    /** The job a player currently has, or null when they have not picked one. */
    public RpgJob of(Player player) {
        PlayerData data = plugin.players().cached(player.getUniqueId());
        return byId(data == null ? readStored(player) : data.jobId());
    }

    public String readStored(Player player) {
        return player.getPersistentDataContainer().get(storageKey, PersistentDataType.STRING);
    }

    /**
     * Assigns a job and re-derives everything from it. Returns false with a
     * message to the player when the job is not available to them.
     */
    public boolean choose(Player player, RpgJob job) {
        PlayerData data = plugin.players().get(player);
        RpgJob current = byId(data.jobId());

        if (current != null && current.id().equals(job.id())) {
            player.sendMessage(ChatColor.YELLOW + "[RPGCore] 이미 " + job.displayName()
                    + ChatColor.YELLOW + " 입니다.");
            return false;
        }
        if (current != null && !plugin.getConfig().getBoolean("jobs.allow-change", true)) {
            player.sendMessage(ChatColor.RED + "[RPGCore] 이 서버에서는 직업을 바꿀 수 없습니다.");
            return false;
        }
        if (data.level() < job.minLevel()) {
            player.sendMessage(ChatColor.RED + "[RPGCore] " + job.displayName() + ChatColor.RED
                    + " 은(는) 레벨 " + job.minLevel() + " 부터 선택할 수 있습니다.");
            return false;
        }

        int cost = current == null ? 0 : Math.max(0, plugin.getConfig().getInt("jobs.change-cost-levels", 0));
        if (cost > 0 && player.getLevel() < cost) {
            player.sendMessage(ChatColor.RED + "[RPGCore] 직업 변경에 경험치 레벨 " + cost
                    + " 이 필요합니다. (보유 " + player.getLevel() + ")");
            return false;
        }
        if (cost > 0) {
            player.setLevel(player.getLevel() - cost);
        }

        apply(player, data, job);
        player.sendMessage(ChatColor.GOLD + "[RPGCore] 직업이 " + job.displayName()
                + ChatColor.GOLD + " 으로 정해졌습니다.");
        return true;
    }

    /** Clears the job, used by /rpgcore reset. */
    public void clear(Player player, PlayerData data) {
        apply(player, data, null);
    }

    private void apply(Player player, PlayerData data, RpgJob job) {
        data.jobId(job == null ? null : job.id());
        if (job == null) {
            player.getPersistentDataContainer().remove(storageKey);
        } else {
            player.getPersistentDataContainer().set(storageKey, PersistentDataType.STRING, job.id());
        }
        plugin.stats().recalculate(player, data);
        plugin.players().flush(player, data);
    }

    /** One-line summary used by the menu lore and /job. */
    public List<String> describe(RpgJob job) {
        List<String> lines = new ArrayList<>(job.description());
        for (StatType type : StatType.values()) {
            int bonus = job.statBonus(type);
            if (bonus != 0) {
                lines.add(ChatColor.AQUA + type.label() + " " + withSign(bonus));
            }
        }
        if (job.weightBonus() != 0) {
            lines.add(ChatColor.AQUA + "소지무게 " + withSign(job.weightBonus()));
        }
        if (job.xpMultiplier() != 1.0D) {
            lines.add(ChatColor.AQUA + "경험치 x" + job.xpMultiplier());
        }
        job.attributeAdd().forEach((key, value) ->
                lines.add(ChatColor.AQUA + key + " " + withSign(value)));
        job.attributeMul().forEach((key, value) ->
                lines.add(ChatColor.AQUA + key + " " + withSign(Math.round(value * 100)) + "%"));
        if (job.minLevel() > 1) {
            lines.add(ChatColor.GRAY + "필요 레벨 " + job.minLevel());
        }
        return lines;
    }

    private static String withSign(double value) {
        String text = value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
        return value >= 0 ? "+" + text : text;
    }
}
