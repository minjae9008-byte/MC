package com.rpgcore.plugin.guild;

import com.rpgcore.plugin.RpgCorePlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reads and writes guilds.yml.
 *
 * A guild file holds three different kinds of thing, and only one of them is
 * cheap to lose: the roster and the claims are small and change rarely, while
 * the vault is a chest full of other people's belongings. So the vault is
 * written whenever it has actually changed and the whole file is written with
 * it - there is no separate "items file" to fall out of step with the roster
 * that says who may open it.
 */
final class GuildStorage {

    private final RpgCorePlugin plugin;
    private final File file;

    GuildStorage(RpgCorePlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "guilds.yml");
    }

    /** Loaded guilds, with their vault contents parked until first opened. */
    record Loaded(Guild guild, List<ItemStack> vault, List<Integer> vaultSlots) {
    }

    List<Loaded> load() {
        List<Loaded> loaded = new ArrayList<>();
        if (!file.isFile()) {
            return loaded;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("guilds");
        if (root == null) {
            return loaded;
        }

        for (String key : root.getKeys(false)) {
            ConfigurationSection node = root.getConfigurationSection(key);
            if (node == null) {
                continue;
            }
            try {
                UUID id = UUID.fromString(key);
                UUID leader = UUID.fromString(node.getString("leader", ""));
                ConfigurationSection members = node.getConfigurationSection("members");
                if (members == null || !members.getKeys(false).contains(leader.toString())) {
                    plugin.getLogger().warning("guilds.yml: guild " + key
                            + " has no leader among its members - skipped.");
                    continue;
                }
                Guild guild = new Guild(id, node.getString("name", "길드"), leader,
                        members.getString(leader.toString(), "?"),
                        node.getLong("created-at", System.currentTimeMillis()));
                guild.gold(node.getInt("gold", 0));
                guild.invested(node.getInt("invested", 0));
                for (String memberKey : members.getKeys(false)) {
                    UUID member = UUID.fromString(memberKey);
                    if (!member.equals(leader)) {
                        guild.add(member, members.getString(memberKey, "?"));
                    }
                }
                readClaim(guild, node.getConfigurationSection("claim"));

                List<ItemStack> stacks = new ArrayList<>();
                List<Integer> slots = new ArrayList<>();
                readVault(node.getConfigurationSection("vault"), stacks, slots);
                loaded.add(new Loaded(guild, stacks, slots));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().severe("guilds.yml: guild " + key + " is malformed ("
                        + e.getMessage() + ") - skipped. Its vault is not lost, but it is"
                        + " unreachable until the entry is repaired by hand.");
            }
        }
        return loaded;
    }

    private void readClaim(Guild guild, ConfigurationSection node) {
        if (node == null) {
            return;
        }
        try {
            guild.claim(new GuildClaim(guild.id(),
                    UUID.fromString(node.getString("world", "")),
                    node.getInt("x"), node.getInt("y"), node.getInt("z"),
                    Math.max(1, node.getInt("radius", 32))));
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("guilds.yml: the claim of guild "
                    + guild.name() + " is malformed - skipped.");
        }
    }

    /** Live wars, which have to outlast a restart or they are a way out of one. */
    List<GuildWar> loadWars() {
        List<GuildWar> loaded = new ArrayList<>();
        if (!file.isFile()) {
            return loaded;
        }
        ConfigurationSection root = YamlConfiguration.loadConfiguration(file)
                .getConfigurationSection("wars");
        if (root == null) {
            return loaded;
        }
        for (String key : root.getKeys(false)) {
            ConfigurationSection node = root.getConfigurationSection(key);
            if (node == null) {
                continue;
            }
            try {
                loaded.add(new GuildWar(
                        UUID.fromString(node.getString("attacker", "")),
                        UUID.fromString(node.getString("defender", "")),
                        node.getLong("declared-at"),
                        node.getLong("fighting-from"),
                        node.getLong("ends-at")));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("guilds.yml: war " + key + " is malformed - skipped.");
            }
        }
        return loaded;
    }

    private void readVault(ConfigurationSection vault, List<ItemStack> stacks, List<Integer> slots) {
        if (vault == null) {
            return;
        }
        for (String slotKey : vault.getKeys(false)) {
            ItemStack stack = vault.getItemStack(slotKey);
            if (stack == null || stack.getType().isAir()) {
                continue;
            }
            try {
                slots.add(Integer.parseInt(slotKey));
                stacks.add(stack);
            } catch (NumberFormatException e) {
                plugin.getLogger().warning("guilds.yml: vault slot '" + slotKey
                        + "' is not a number - that stack is skipped.");
            }
        }
    }

    /**
     * War cooldowns. Persisted because a restart is routine and a day-long
     * cooldown that resets with the server is not a cooldown.
     */
    Map<String, Long> loadCooldowns() {
        Map<String, Long> cooldowns = new java.util.HashMap<>();
        if (!file.isFile()) {
            return cooldowns;
        }
        ConfigurationSection root = YamlConfiguration.loadConfiguration(file)
                .getConfigurationSection("war-cooldowns");
        if (root == null) {
            return cooldowns;
        }
        for (String key : root.getKeys(false)) {
            cooldowns.put(key.replace('_', ':'), root.getLong(key));
        }
        return cooldowns;
    }

    /**
     * Assembles the whole file in memory. Called on the main thread, because
     * it reads live vault inventories; the expensive half - turning this tree
     * into YAML text - happens off it. See {@link com.rpgcore.plugin.util.DeferredSave}.
     */
    YamlConfiguration build(Collection<Guild> guilds, Collection<GuildWar> wars,
                            Map<String, Long> cooldowns) {
        YamlConfiguration yaml = new YamlConfiguration();
        int index = 0;
        for (GuildWar war : wars) {
            if (war.over()) {
                // A settled war is history; it only has to survive long enough
                // to be announced, which happens before this ever runs again.
                continue;
            }
            String path = "wars." + index++;
            yaml.set(path + ".attacker", war.attacker().toString());
            yaml.set(path + ".defender", war.defender().toString());
            yaml.set(path + ".declared-at", war.declaredAtMs());
            yaml.set(path + ".fighting-from", war.fightingFromMs());
            yaml.set(path + ".ends-at", war.endsAtMs());
        }
        for (Map.Entry<String, Long> cooldown : cooldowns.entrySet()) {
            // ':' separates the pair, and YAML paths split on '.', so neither
            // character may reach the key - '_' is safe in both.
            yaml.set("war-cooldowns." + cooldown.getKey().replace(':', '_'), cooldown.getValue());
        }
        for (Guild guild : guilds) {
            String path = "guilds." + guild.id();
            yaml.set(path + ".name", guild.name());
            yaml.set(path + ".leader", guild.leader().toString());
            yaml.set(path + ".created-at", guild.createdAtMs());
            for (UUID member : guild.ordered()) {
                yaml.set(path + ".members." + member, guild.nameOf(member));
            }
            yaml.set(path + ".gold", guild.gold());
            yaml.set(path + ".invested", guild.invested());
            GuildClaim claim = guild.claim();
            if (claim != null) {
                yaml.set(path + ".claim.world", claim.worldId().toString());
                yaml.set(path + ".claim.x", claim.x());
                yaml.set(path + ".claim.y", claim.y());
                yaml.set(path + ".claim.z", claim.z());
                yaml.set(path + ".claim.radius", claim.radius());
            }
            writeVault(yaml, path, guild);
        }
        return yaml;
    }

    /**
     * Writes the vault by slot rather than as a list, so an empty slot costs
     * nothing and a stack never shifts position between saves - a vault a
     * member has arranged stays arranged.
     */
    private void writeVault(YamlConfiguration yaml, String path, Guild guild) {
        Inventory vault = guild.vaultOrNull();
        if (vault == null) {
            // Never opened this session: whatever was read at load is still
            // the truth, and it was put back into the parked contents.
            for (int slot = 0; slot < guild.parkedVault().length; slot++) {
                ItemStack stack = guild.parkedVault()[slot];
                if (stack != null && !stack.getType().isAir()) {
                    yaml.set(path + ".vault." + slot, stack.clone());
                }
            }
            return;
        }
        ItemStack[] contents = vault.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack stack = contents[slot];
            if (stack != null && !stack.getType().isAir()) {
                // Cloned, not referenced. The stack is turned into YAML on a
                // writer thread some time after this returns, and the live one
                // belongs to an inventory members are still clicking in.
                yaml.set(path + ".vault." + slot, stack.clone());
            }
        }
    }
}
