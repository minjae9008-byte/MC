package com.rpgcore.plugin.party;

import com.rpgcore.plugin.RpgCorePlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Reads and writes parties.yml.
 *
 * Parties are the one thing in RPGCore that fits neither the integer-only
 * scoreboard nor a single player's persistent data - they are a named group
 * spanning several players - so they get a small file of their own.
 */
final class PartyStorage {

    private final RpgCorePlugin plugin;
    private final File file;

    PartyStorage(RpgCorePlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "parties.yml");
    }

    List<Party> load() {
        List<Party> parties = new ArrayList<>();
        if (!file.isFile()) {
            return parties;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("parties");
        if (root == null) {
            return parties;
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
                    plugin.getLogger().warning("parties.yml: party " + key
                            + " has no leader among its members - skipped.");
                    continue;
                }
                Party party = new Party(id, node.getString("name", "파티"), leader,
                        members.getString(leader.toString(), "?"));
                for (String memberKey : members.getKeys(false)) {
                    UUID member = UUID.fromString(memberKey);
                    if (!member.equals(leader)) {
                        party.add(member, members.getString(memberKey, "?"));
                    }
                }
                parties.add(party);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("parties.yml: malformed entry " + key + " - skipped.");
            }
        }
        return parties;
    }

    /** Assembles the file in memory; the YAML dump happens off the main thread. */
    YamlConfiguration build(Iterable<Party> parties) {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Party party : parties) {
            String path = "parties." + party.id();
            yaml.set(path + ".name", party.name());
            yaml.set(path + ".leader", party.leader().toString());
            for (UUID member : party.ordered()) {
                yaml.set(path + ".members." + member, party.nameOf(member));
            }
        }
        return yaml;
    }
}
