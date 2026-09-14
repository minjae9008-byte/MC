package com.rpgcore.plugin.guild;

import com.rpgcore.plugin.RpgCorePlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
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
                for (String memberKey : members.getKeys(false)) {
                    UUID member = UUID.fromString(memberKey);
                    if (!member.equals(leader)) {
                        guild.add(member, members.getString(memberKey, "?"));
                    }
                }
                readClaims(guild, node.getConfigurationSection("claims"));

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

    private void readClaims(Guild guild, ConfigurationSection claims) {
        if (claims == null) {
            return;
        }
        for (String claimKey : claims.getKeys(false)) {
            ConfigurationSection node = claims.getConfigurationSection(claimKey);
            if (node == null) {
                continue;
            }
            try {
                guild.addClaim(new GuildClaim(guild.id(),
                        UUID.fromString(node.getString("world", "")),
                        node.getInt("x"), node.getInt("y"), node.getInt("z"),
                        Math.max(1, node.getInt("radius", 32))));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("guilds.yml: claim " + claimKey + " of guild "
                        + guild.name() + " is malformed - skipped.");
            }
        }
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

    void save(Collection<Guild> guilds) {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Guild guild : guilds) {
            String path = "guilds." + guild.id();
            yaml.set(path + ".name", guild.name());
            yaml.set(path + ".leader", guild.leader().toString());
            yaml.set(path + ".created-at", guild.createdAtMs());
            for (UUID member : guild.ordered()) {
                yaml.set(path + ".members." + member, guild.nameOf(member));
            }
            int index = 0;
            for (GuildClaim claim : guild.claims()) {
                String claimPath = path + ".claims." + index++;
                yaml.set(claimPath + ".world", claim.worldId().toString());
                yaml.set(claimPath + ".x", claim.x());
                yaml.set(claimPath + ".y", claim.y());
                yaml.set(claimPath + ".z", claim.z());
                yaml.set(claimPath + ".radius", claim.radius());
            }
            writeVault(yaml, path, guild);
        }
        try {
            if (!plugin.getDataFolder().isDirectory() && !plugin.getDataFolder().mkdirs()) {
                plugin.getLogger().severe("Could not create the plugin folder - guilds will not persist.");
                return;
            }
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not write guilds.yml: " + e.getMessage()
                    + " - guild vaults and claims will be lost on restart.");
        }
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
                    yaml.set(path + ".vault." + slot, stack);
                }
            }
            return;
        }
        ItemStack[] contents = vault.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack stack = contents[slot];
            if (stack != null && !stack.getType().isAir()) {
                yaml.set(path + ".vault." + slot, stack);
            }
        }
    }
}
