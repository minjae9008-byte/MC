package com.rpgcore.plugin.progress;

import com.rpgcore.plugin.RpgCorePlugin;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The tags players wear next to their name - [전설], [낚시왕] and the rest.
 *
 * A title is only ever stored as its id. The text and colour live in the
 * config that defines it (an achievement, or a collection category), so
 * renaming [전설] in config renames it for everyone who already earned it
 * instead of leaving old text stamped on old players.
 *
 * Which titles a player has earned, and which one they are wearing, go in
 * their own persistent data container - the same place their job lives, so
 * still no extra data file.
 */
public final class TitleService {

    /** One title as declared by whatever grants it. */
    public record Title(String id, String display, Material icon, String requirement) {
    }

    private final RpgCorePlugin plugin;
    private final NamespacedKey earnedKey;
    private final NamespacedKey wornKey;
    /** Declared titles in declaration order, so the menu reads the same way twice. */
    private final Map<String, Title> titles = new LinkedHashMap<>();
    /**
     * What each online player has earned, and what they are wearing.
     *
     * The nameplate asks for the worn title once a second per player, which is
     * no place to be re-reading and re-splitting a stored string; both are read
     * on join and written through on change.
     */
    private final Map<UUID, Set<String>> earnedCache = new ConcurrentHashMap<>();
    private final Map<UUID, String> wornCache = new ConcurrentHashMap<>();

    public TitleService(RpgCorePlugin plugin) {
        this.plugin = plugin;
        this.earnedKey = new NamespacedKey(plugin, "titles");
        this.wornKey = new NamespacedKey(plugin, "title");
    }

    /** Dropped and rebuilt on every reload, since the definitions can change. */
    public void clear() {
        titles.clear();
    }

    public void register(String id, String display, Material icon, String requirement) {
        if (id == null || display == null || display.isBlank()) {
            return;
        }
        String key = id.toLowerCase(Locale.ROOT);
        titles.put(key, new Title(key,
                ChatColor.translateAlternateColorCodes('&', display), icon, requirement));
    }

    public Title byId(String id) {
        return id == null ? null : titles.get(id.toLowerCase(Locale.ROOT));
    }

    /**
     * By id, or by the text a player can actually see. Someone wearing
     * [강태공] will type that, not the config key it happens to live under.
     */
    public Title resolve(String needle) {
        Title byId = byId(needle);
        if (byId != null || needle == null) {
            return byId;
        }
        String plain = strip(needle);
        for (Title title : titles.values()) {
            if (strip(title.display()).equalsIgnoreCase(plain)) {
                return title;
            }
        }
        return null;
    }

    /** Colour codes and brackets off, so "[강태공]" and "강태공" both match. */
    private static String strip(String text) {
        return ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', text))
                .replace("[", "").replace("]", "").trim();
    }

    public int count() {
        return titles.size();
    }

    /** Reads a player's titles into memory on join. */
    public void load(Player player) {
        earnedCache.put(player.getUniqueId(),
                split(player.getPersistentDataContainer().get(earnedKey, PersistentDataType.STRING)));
        wornCache.put(player.getUniqueId(), readWorn(player));
    }

    /**
     * "" means "wearing nothing", which is a different thing from "not loaded"
     * - the map having no entry at all is what triggers a read.
     */
    private String readWorn(Player player) {
        String worn = player.getPersistentDataContainer().get(wornKey, PersistentDataType.STRING);
        return worn == null ? "" : worn.toLowerCase(Locale.ROOT);
    }

    public void unload(Player player) {
        earnedCache.remove(player.getUniqueId());
        wornCache.remove(player.getUniqueId());
    }

    public Set<String> earned(Player player) {
        return earnedCache.computeIfAbsent(player.getUniqueId(), uuid ->
                split(player.getPersistentDataContainer().get(earnedKey, PersistentDataType.STRING)));
    }

    public boolean hasEarned(Player player, String id) {
        return id != null && earned(player).contains(id.toLowerCase(Locale.ROOT));
    }

    /**
     * Records a title as earned. Returns false when the player already had it,
     * so callers can stay quiet rather than re-announcing.
     */
    public boolean award(Player player, String id) {
        Title title = byId(id);
        if (title == null) {
            return false;
        }
        Set<String> owned = earned(player);
        if (!owned.add(title.id())) {
            return false;
        }
        player.getPersistentDataContainer().set(earnedKey, PersistentDataType.STRING,
                String.join(",", owned));
        // First title earned is worn straight away: a player who never opens
        // the menu should still see the reward above their head.
        if (worn(player) == null) {
            wear(player, title.id());
        }
        return true;
    }

    /** The title a player is wearing, or null - including when it was revoked. */
    public Title worn(Player player) {
        Title title = byId(wornCache.computeIfAbsent(player.getUniqueId(), uuid -> readWorn(player)));
        return title != null && hasEarned(player, title.id()) ? title : null;
    }

    /** Passing null takes the title off. */
    public void wear(Player player, String id) {
        if (id == null) {
            player.getPersistentDataContainer().remove(wornKey);
            wornCache.put(player.getUniqueId(), "");
            return;
        }
        Title title = byId(id);
        if (title == null || !hasEarned(player, title.id())) {
            return;
        }
        player.getPersistentDataContainer().set(wornKey, PersistentDataType.STRING, title.id());
        wornCache.put(player.getUniqueId(), title.id());
    }

    /** Clears everything a player earned, for /rpgcore reset. */
    public void reset(Player player) {
        player.getPersistentDataContainer().remove(earnedKey);
        player.getPersistentDataContainer().remove(wornKey);
        earnedCache.put(player.getUniqueId(), new LinkedHashSet<>());
        wornCache.put(player.getUniqueId(), "");
    }

    private Set<String> split(String raw) {
        Set<String> out = new LinkedHashSet<>();
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

    /** Earned titles first, then the ones still to win. */
    public List<Title> ordered(Player player) {
        Set<String> owned = earned(player);
        List<Title> out = new ArrayList<>();
        for (Title title : titles.values()) {
            if (owned.contains(title.id())) {
                out.add(title);
            }
        }
        for (Title title : titles.values()) {
            if (!owned.contains(title.id())) {
                out.add(title);
            }
        }
        return out;
    }
}
