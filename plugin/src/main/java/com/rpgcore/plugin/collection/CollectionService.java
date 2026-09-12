package com.rpgcore.plugin.collection;

import com.rpgcore.plugin.RpgCorePlugin;
import com.rpgcore.plugin.config.ConfigFiles;
import com.rpgcore.plugin.progress.CounterType;
import com.rpgcore.plugin.util.MaterialSets;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The collection log: wood, ore, wool, fish and whatever else an operator adds.
 *
 * Discovery rides along on the encumbrance scan, which already has every stack
 * a player carries in hand - so picking something up, crafting it, fishing it
 * out of a lake or pulling it from a chest all register it, with no listener
 * per source and no pass of its own. The check per stack is one EnumMap lookup
 * against a set held in memory; the persistent write only happens on the rare
 * tick where something genuinely new turned up.
 */
public final class CollectionService {

    public static final String FILE = "collection.yml";

    private final RpgCorePlugin plugin;
    private final NamespacedKey storageKey;
    private final Map<String, CollectionCategory> categories = new LinkedHashMap<>();
    /**
     * Material -> the category it belongs to. Flattened at load so the scan
     * never walks the category list.
     */
    private final Map<Material, CollectionCategory> index = new EnumMap<>(Material.class);
    /** Per-player discoveries, loaded on join so the scan never touches storage. */
    private final Map<UUID, Set<Material>> found = new ConcurrentHashMap<>();

    public CollectionService(RpgCorePlugin plugin) {
        this.plugin = plugin;
        this.storageKey = new NamespacedKey(plugin, "collection");
    }

    public void load() {
        categories.clear();
        index.clear();
        if (!plugin.rpgConfig().collectionEnabled()) {
            plugin.getLogger().info("The collection log is disabled (settings.yml features.collection).");
            return;
        }

        FileConfiguration config = ConfigFiles.load(plugin, FILE);
        ConfigurationSection list = config.getConfigurationSection("categories");
        if (list == null) {
            plugin.getLogger().warning(FILE + " has no 'categories:' section - the collection log is empty.");
            return;
        }
        for (String id : list.getKeys(false)) {
            ConfigurationSection node = list.getConfigurationSection(id);
            if (node != null) {
                parse(id.toLowerCase(Locale.ROOT), node);
            }
        }

        int entries = index.size();
        if (entries == 0) {
            plugin.getLogger().warning("The collection log resolved to 0 entries - check the item lists in "
                    + FILE + ", then /rpgcore check.");
        } else {
            plugin.getLogger().info("Collection log loaded: " + categories.size() + " categories, "
                    + entries + " entries.");
        }
    }

    private void parse(String id, ConfigurationSection node) {
        String context = FILE + " categories." + id;

        Material icon = Material.matchMaterial(node.getString("icon", "minecraft:book"));
        if (icon == null) {
            plugin.getLogger().warning(context + ": unknown icon - using book.");
            icon = Material.BOOK;
        }

        Set<Material> resolved = MaterialSets.resolve(plugin, Tag.REGISTRY_ITEMS,
                node.getStringList("items"), context);
        if (resolved.isEmpty()) {
            plugin.getLogger().warning(context + ": no items resolved - the page would always be complete, skipped.");
            return;
        }
        // A material may only belong to one page, or completing two pages at
        // once would make "collected" counts disagree with the pages.
        List<Material> entries = new ArrayList<>();
        for (Material material : resolved) {
            if (index.containsKey(material)) {
                plugin.getLogger().warning(context + ": " + material.getKey()
                        + " is already in '" + index.get(material).id() + "' - skipped here.");
                continue;
            }
            entries.add(material);
        }
        if (entries.isEmpty()) {
            return;
        }

        CollectionCategory category = new CollectionCategory(id,
                ChatColor.translateAlternateColorCodes('&', node.getString("display", id)),
                icon,
                List.copyOf(entries),
                node.getString("title"),
                Math.max(0, node.getInt("reward-gold", 0)),
                Math.max(0, node.getInt("reward-xp", 0)));

        categories.put(id, category);
        for (Material material : entries) {
            index.put(material, category);
        }
        if (category.grantsTitle()) {
            plugin.titles().register(id, category.title(), icon,
                    category.display() + " 완성 (" + category.size() + "종)");
        }
    }

    /** Reads a player's discoveries into memory on join. */
    public void load(Player player) {
        Set<Material> set = EnumSet.noneOf(Material.class);
        String raw = player.getPersistentDataContainer().get(storageKey, PersistentDataType.STRING);
        if (raw != null && !raw.isBlank()) {
            for (String part : raw.split(",")) {
                Material material = Material.matchMaterial(part.trim());
                // A material the server no longer has is dropped silently:
                // an old log should not spam the console on every login.
                if (material != null) {
                    set.add(material);
                }
            }
        }
        found.put(player.getUniqueId(), set);
        if (raw == null) {
            seed(player, set);
        }
    }

    /**
     * A player who has never had a log gets whatever they are already carrying
     * registered in one silent go. Without this, everybody on an existing
     * server would be met by forty discovery lines the first time the scan ran
     * - and the rewards would arrive for a shelf they filled months ago.
     */
    private void seed(Player player, Set<Material> set) {
        for (org.bukkit.inventory.ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && index.containsKey(stack.getType())) {
                set.add(stack.getType());
            }
        }
        save(player, set);
    }

    public void unload(Player player) {
        found.remove(player.getUniqueId());
    }

    /**
     * Called for every carried stack by the encumbrance scan. Returns true when
     * this was genuinely new, which is the caller's cue that a save happened.
     */
    public boolean record(Player player, Material material) {
        CollectionCategory category = index.get(material);
        if (category == null) {
            return false;
        }
        Set<Material> set = found.get(player.getUniqueId());
        if (set == null || !set.add(material)) {
            return false;
        }
        save(player, set);
        plugin.achievements().bump(player, CounterType.COLLECTED, 1);

        int have = countIn(set, category);
        if (have >= category.size()) {
            complete(player, category);
        } else {
            player.sendMessage(ChatColor.AQUA + "[도감] " + ChatColor.WHITE + name(material)
                    + ChatColor.AQUA + " 등록! " + ChatColor.GRAY + "(" + category.display()
                    + ChatColor.GRAY + " " + have + "/" + category.size() + ")");
        }
        return true;
    }

    private void complete(Player player, CollectionCategory category) {
        player.sendMessage("");
        player.sendMessage(ChatColor.AQUA + "  ★ " + ChatColor.WHITE + category.display()
                + ChatColor.AQUA + " 을(를) 전부 모았습니다! (" + category.size() + "종)");
        List<String> rewards = new ArrayList<>();
        if (category.rewardGold() > 0) {
            rewards.add(plugin.economy().format(category.rewardGold()));
        }
        if (category.rewardXp() > 0) {
            rewards.add(ChatColor.GREEN + "XP " + category.rewardXp());
        }
        if (category.grantsTitle() && plugin.titles().award(player, category.id())) {
            rewards.add(ChatColor.GRAY + "칭호 " + plugin.titles().byId(category.id()).display());
        }
        if (!rewards.isEmpty()) {
            player.sendMessage(ChatColor.GRAY + "    보상: " + String.join(ChatColor.GRAY + ", ", rewards));
        }
        player.sendMessage("");
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0F, 1.2F);

        plugin.economy().give(player, category.rewardGold());
        if (category.rewardXp() > 0) {
            plugin.stats().addXp(player, category.rewardXp());
        }
        for (Player other : plugin.getServer().getOnlinePlayers()) {
            if (!other.equals(player)) {
                other.sendMessage(ChatColor.AQUA + "[도감] " + ChatColor.WHITE + player.getName()
                        + ChatColor.AQUA + " 님이 " + ChatColor.WHITE + category.display()
                        + ChatColor.AQUA + " 을(를) 완성했습니다!");
            }
        }
    }

    private void save(Player player, Set<Material> set) {
        List<String> names = new ArrayList<>(set.size());
        for (Material material : set) {
            names.add(material.getKey().toString());
        }
        player.getPersistentDataContainer().set(storageKey, PersistentDataType.STRING,
                String.join(",", names));
    }

    public boolean has(Player player, Material material) {
        Set<Material> set = found.get(player.getUniqueId());
        return set != null && set.contains(material);
    }

    public int countIn(Player player, CollectionCategory category) {
        Set<Material> set = found.get(player.getUniqueId());
        return set == null ? 0 : countIn(set, category);
    }

    private int countIn(Set<Material> set, CollectionCategory category) {
        int have = 0;
        for (Material material : category.entries()) {
            if (set.contains(material)) {
                have++;
            }
        }
        return have;
    }

    /** Clears a player's log, for /rpgcore reset. */
    public void reset(Player player) {
        player.getPersistentDataContainer().remove(storageKey);
        found.put(player.getUniqueId(), EnumSet.noneOf(Material.class));
    }

    public List<CollectionCategory> categories() {
        return List.copyOf(categories.values());
    }

    public CollectionCategory byId(String id) {
        return id == null ? null : categories.get(id.toLowerCase(Locale.ROOT));
    }

    public int entryCount() {
        return index.size();
    }

    public boolean isEmpty() {
        return categories.isEmpty();
    }

    /** As {@link #name(Material)}, for a stack. */
    public static String nameOf(org.bukkit.inventory.ItemStack stack) {
        return stack == null ? "?" : name(stack.getType());
    }

    /** "Oak Log" rather than "minecraft:oak_log", without a language file. */
    public static String name(Material material) {
        String[] words = material.getKey().getKey().split("_");
        StringBuilder out = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }
}
