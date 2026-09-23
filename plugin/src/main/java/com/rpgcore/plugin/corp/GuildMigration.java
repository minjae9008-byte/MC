package com.rpgcore.plugin.corp;

import com.rpgcore.plugin.RpgCorePlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.Locale;
import java.util.UUID;

/**
 * Turns the guilds a server already had into companies, once.
 *
 * Guilds are gone, but what they held was not the plugin's to delete: a vault
 * full of members' items and a treasury they put gold into. Wiping the file
 * would have been one line and would have cost real people real things.
 *
 * So each guild becomes a company with the same name and the same people -
 * the leader as CEO, everybody else on staff - and the treasury plus whatever
 * was sunk into territory becomes its opening cash. Vault contents go to the
 * leader's mailbox rather than into the warehouse, because a company
 * warehouse holds commodity counts and a vault holds actual items, enchanted
 * and named ones included.
 *
 * The file is renamed rather than deleted when it is done, so an operator who
 * wants to check what was in it still can, and so this can never run twice.
 */
final class GuildMigration {

    private GuildMigration() {
    }

    /**
     * @param publish writes the converted companies to disk; called before
     *                the old file is renamed, so a crash in between leaves
     *                the guilds to be converted again rather than gone
     */
    static void run(RpgCorePlugin plugin, CorpService corps, Runnable publish) {
        File file = new File(plugin.getDataFolder(), "guilds.yml");
        if (!file.isFile()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("guilds");
        int converted = 0;
        int itemsReturned = 0;

        if (root != null) {
            for (String key : root.getKeys(false)) {
                ConfigurationSection node = root.getConfigurationSection(key);
                if (node == null) {
                    continue;
                }
                try {
                    UUID leader = UUID.fromString(node.getString("leader", ""));
                    String name = uniqueName(corps, node.getString("name", "회사"));
                    Company company = new Company(UUID.randomUUID(), name,
                            ticker(corps, name), leader, false,
                            node.getLong("created-at", System.currentTimeMillis()));
                    // Territory investment comes back as cash: the land is
                    // gone, so holding on to what it cost would be a quiet
                    // confiscation.
                    company.cash((long) node.getInt("gold", 0) + node.getInt("invested", 0));
                    company.sharesIssued(corps.config().founderShares());
                    company.setHolding(leader, corps.config().founderShares());
                    company.sellPercent(corps.config().defaultSellPercent());
                    company.autoBuyInputs(corps.config().defaultAutoBuyInputs());
                    company.dividendPercent(corps.config().defaultDividendPercent());

                    ConfigurationSection members = node.getConfigurationSection("members");
                    if (members != null) {
                        for (String member : members.getKeys(false)) {
                            UUID uuid = UUID.fromString(member);
                            company.employees().put(uuid, new Company.Employee(
                                    members.getString(member, "?"),
                                    uuid.equals(leader) ? Company.Role.CEO : Company.Role.STAFF,
                                    0, System.currentTimeMillis()));
                        }
                    }
                    company.employees().putIfAbsent(leader, new Company.Employee(
                            "?", Company.Role.CEO, 0, System.currentTimeMillis()));

                    ConfigurationSection vault = node.getConfigurationSection("vault");
                    if (vault != null) {
                        for (String slot : vault.getKeys(false)) {
                            ItemStack stack = vault.getItemStack(slot);
                            if (stack == null || stack.getType().isAir()) {
                                // Bukkit logs its own reason above this; say
                                // out loud that somebody's item did not come
                                // back, because that is the part that matters.
                                plugin.getLogger().severe("guilds.yml: vault slot " + slot
                                        + " of guild '" + name + "' could not be read - that item"
                                        + " has NOT been returned. It is still in"
                                        + " guilds.yml.migrated.");
                                continue;
                            }
                            plugin.mailbox().give(leader, stack, "길드 보관함 반환");
                            itemsReturned++;
                        }
                    }
                    corps.companyMap().put(company.id(), company);
                    converted++;
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().severe("guilds.yml: guild " + key + " could not be converted ("
                            + e.getMessage() + "). Its vault has NOT been returned - the file is "
                            + "kept as guilds.yml.migrated so it can be sorted out by hand.");
                }
            }
        }

        // Written before the old file is touched. The other way round, a
        // crash in the gap would leave guilds.yml renamed and the companies
        // it became never saved - every guild on the server gone.
        if (converted > 0) {
            publish.run();
        }

        File archive = new File(plugin.getDataFolder(), "guilds.yml.migrated");
        if (!file.renameTo(archive)) {
            plugin.getLogger().severe("Could not rename guilds.yml after converting it. "
                    + "Move it by hand, or the conversion will run again on the next start.");
        }
        plugin.getLogger().info("Guilds converted to companies: " + converted
                + " (vault items returned to leaders: " + itemsReturned + ").");
    }

    private static String uniqueName(CorpService corps, String wanted) {
        String base = wanted.length() > corps.config().nameMaxLength()
                ? wanted.substring(0, corps.config().nameMaxLength()) : wanted;
        String candidate = base;
        int suffix = 2;
        while (corps.byName(candidate) != null) {
            candidate = base + suffix++;
        }
        return candidate;
    }

    private static String ticker(CorpService corps, String name) {
        StringBuilder out = new StringBuilder();
        for (char c : name.toUpperCase(Locale.ROOT).toCharArray()) {
            if (c >= 'A' && c <= 'Z') {
                out.append(c);
            }
            if (out.length() >= 4) {
                break;
            }
        }
        String base = out.length() >= 2 ? out.toString()
                : "GLD" + Math.abs(name.hashCode() % 10);
        String candidate = base;
        int suffix = 2;
        while (corps.byTicker(candidate) != null) {
            candidate = base.substring(0, Math.min(3, base.length())) + suffix++;
        }
        return candidate;
    }
}
