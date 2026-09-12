package com.rpgcore.plugin.duel;

import com.rpgcore.plugin.RpgCorePlugin;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Item stakes that had nowhere to go when their duel ended.
 *
 * A duel can perfectly well end while both sides are gone: a shutdown, a
 * /rpgcore reload, or the timeout firing after the last of the two
 * disconnected. Logging the stake and dropping it on the floor of the JVM
 * would make "the stake is the only thing at risk" a lie, so it is written
 * here instead and handed back the next time that player logs in.
 *
 * Gold waits here too, rather than going straight onto the scoreboard mirror.
 * The mirror is keyed by name, and a player who is offline is exactly the
 * player who might come back under a different one - a credit written to the
 * name they no longer use, or to the name they have just taken from someone
 * else, is a credit in the wrong account. This file is keyed by UUID, so the
 * refund reaches whoever actually placed the wager.
 *
 * The file is the plugin's only asynchronous promise, so it is flushed on
 * every change rather than at shutdown - the case this exists for is precisely
 * the one where shutdown may not be orderly.
 */
final class PendingStakeStore {

    private final RpgCorePlugin plugin;
    private final File file;
    /** owner -> the stacks still owed to them. */
    private final Map<UUID, List<ItemStack>> owed = new LinkedHashMap<>();
    /** owner -> gold still owed to them. */
    private final Map<UUID, Integer> owedGold = new LinkedHashMap<>();

    PendingStakeStore(RpgCorePlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "pending-stakes.yml");
    }

    void load() {
        owed.clear();
        owedGold.clear();
        if (!file.isFile()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("owed");
        if (root == null) {
            return;
        }
        for (String key : root.getKeys(false)) {
            UUID uuid;
            try {
                uuid = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("pending-stakes.yml: malformed entry " + key + " - skipped.");
                continue;
            }
            List<ItemStack> stacks = new ArrayList<>();
            for (Object entry : root.getList(key, List.of())) {
                if (entry instanceof ItemStack stack && !stack.getType().isAir()) {
                    stacks.add(stack);
                }
            }
            if (!stacks.isEmpty()) {
                owed.put(uuid, stacks);
            }
        }
        ConfigurationSection goldRoot = yaml.getConfigurationSection("gold");
        if (goldRoot != null) {
            for (String key : goldRoot.getKeys(false)) {
                try {
                    int amount = goldRoot.getInt(key, 0);
                    if (amount > 0) {
                        owedGold.put(UUID.fromString(key), amount);
                    }
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("pending-stakes.yml: malformed gold entry " + key + " - skipped.");
                }
            }
        }
        int waiting = owed.size() + owedGold.size();
        if (waiting > 0) {
            plugin.getLogger().info("Duel stakes awaiting return: " + owed.size()
                    + " item holding(s), " + owedGold.size() + " gold holding(s).");
        }
    }

    /** Remembers a stack for a player who was not there to receive it. */
    void hold(UUID uuid, ItemStack stack) {
        owed.computeIfAbsent(uuid, k -> new ArrayList<>()).add(stack.clone());
        save();
    }

    /** Same, for gold. Saturating, because the balance it feeds is an int. */
    void holdGold(UUID uuid, int amount) {
        if (amount <= 0) {
            return;
        }
        owedGold.merge(uuid, amount, (a, b) -> (int) Math.min((long) a + b, Integer.MAX_VALUE));
        save();
    }

    /**
     * Hands back everything owed to a player who has just joined. Anything
     * their inventory has no room for goes on the floor at their feet, which
     * is what vanilla does with a full inventory and is still better than
     * holding it back indefinitely.
     */
    void handOver(Player player) {
        List<ItemStack> stacks = owed.remove(player.getUniqueId());
        Integer gold = owedGold.remove(player.getUniqueId());
        if ((stacks == null || stacks.isEmpty()) && (gold == null || gold <= 0)) {
            return;
        }
        save();
        int returned = 0;
        if (stacks != null) {
            for (ItemStack stack : stacks) {
                for (ItemStack leftover : player.getInventory().addItem(stack).values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), leftover);
                }
            }
            returned = stacks.size();
        }
        if (gold != null && gold > 0) {
            // refund, not give: this is a wager coming back, not gold earned,
            // so it must not count towards the lifetime-earned achievement.
            plugin.economy().refund(player, gold);
        }
        player.sendMessage(ChatColor.GREEN + "[대결] 접속 중이 아닐 때 끝난 대결에서 건 것을 돌려받았습니다."
                + (returned > 0 ? ChatColor.GRAY + " (아이템 " + returned + "개)" : "")
                + (gold != null && gold > 0 ? ChatColor.GRAY + " (" + gold + " 골드)" : ""));
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, List<ItemStack>> entry : owed.entrySet()) {
            yaml.set("owed." + entry.getKey(), entry.getValue());
        }
        for (Map.Entry<UUID, Integer> entry : owedGold.entrySet()) {
            yaml.set("gold." + entry.getKey(), entry.getValue());
        }
        try {
            if (!plugin.getDataFolder().isDirectory() && !plugin.getDataFolder().mkdirs()) {
                plugin.getLogger().severe("Could not create the plugin folder - held duel stakes will be lost.");
                return;
            }
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not write pending-stakes.yml: " + e.getMessage()
                    + " - held duel stakes will be lost on restart.");
        }
    }
}
