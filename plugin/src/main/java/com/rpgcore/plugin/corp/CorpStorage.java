package com.rpgcore.plugin.corp;

import com.rpgcore.plugin.RpgCorePlugin;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Reads and writes companies.yml.
 *
 * This file holds people's shareholdings and a company's cash, so a line that
 * will not parse is reported loudly and the rest of the company is kept,
 * rather than the whole record being dropped quietly. A dropped company is
 * every investor in it losing their stake without being told.
 */
final class CorpStorage {

    static final String FILE = "companies.yml";

    private final RpgCorePlugin plugin;
    private final File file;

    CorpStorage(RpgCorePlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), FILE);
    }

    void restore(CorpService service) {
        if (!file.isFile()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        service.seededFlag(yaml.getBoolean("seeded", false));

        ConfigurationSection root = yaml.getConfigurationSection("companies");
        if (root != null) {
            for (String key : root.getKeys(false)) {
                ConfigurationSection node = root.getConfigurationSection(key);
                if (node == null) {
                    continue;
                }
                try {
                    service.companyMap().put(UUID.fromString(key), read(UUID.fromString(key), node));
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().severe(FILE + ": company " + key + " is malformed ("
                            + e.getMessage() + ") - skipped.");
                }
            }
        }

        for (Map<?, ?> row : yaml.getMapList("offers")) {
            try {
                service.offerList().add(new CorpService.Offer(
                        UUID.fromString(String.valueOf(row.get("id"))),
                        UUID.fromString(String.valueOf(row.get("acquirer"))),
                        UUID.fromString(String.valueOf(row.get("target"))),
                        number(row.get("premium")).doubleValue(),
                        number(row.get("price")).longValue(),
                        number(row.get("total")).longValue(),
                        number(row.get("expires")).longValue()));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning(FILE + ": a takeover offer is malformed - skipped.");
            }
        }
        plugin.getLogger().info("Company register restored from " + FILE + ".");
    }

    private Company read(UUID id, ConfigurationSection node) {
        String ceoRaw = node.getString("ceo");
        Company company = new Company(id,
                node.getString("name", "회사"),
                node.getString("ticker", "----"),
                ceoRaw == null || ceoRaw.isBlank() ? null : UUID.fromString(ceoRaw),
                node.getBoolean("npc", false),
                node.getLong("created-at", System.currentTimeMillis()));
        company.cash(node.getLong("cash", 0));
        company.sharesIssued(node.getLong("shares", 0));
        company.sellPercent(node.getInt("sell-percent", 100));
        company.autoBuyInputs(node.getBoolean("auto-buy-inputs", true));
        company.dividendPercent(node.getInt("dividend-percent", 0));
        company.sharePrice(node.getDouble("share-price", 0));
        company.sentiment(node.getDouble("sentiment", 1.0));
        company.restoreFinancials(
                node.getLong("last-revenue", 0), node.getLong("last-costs", 0),
                node.getLong("last-wages", 0), node.getLong("last-profit", 0),
                node.getLong("lifetime-revenue", 0), node.getLong("lifetime-costs", 0),
                node.getLong("lifetime-dividends", 0), node.getInt("days", 0));

        ConfigurationSection staff = node.getConfigurationSection("employees");
        if (staff != null) {
            for (String key : staff.getKeys(false)) {
                ConfigurationSection row = staff.getConfigurationSection(key);
                if (row == null) {
                    continue;
                }
                try {
                    Company.Role role = Company.Role.valueOf(
                            row.getString("role", "STAFF").toUpperCase(Locale.ROOT));
                    company.employees().put(UUID.fromString(key), new Company.Employee(
                            row.getString("name", "?"), role,
                            row.getInt("unpaid-days", 0),
                            row.getLong("joined-at", System.currentTimeMillis())));
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning(FILE + ": employee " + key + " of "
                            + company.name() + " is malformed - skipped.");
                }
            }
        }

        ConfigurationSection holders = node.getConfigurationSection("holders");
        if (holders != null) {
            for (String key : holders.getKeys(false)) {
                try {
                    company.setHolding(UUID.fromString(key), holders.getLong(key));
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().severe(FILE + ": shareholder " + key + " of "
                            + company.name() + " is malformed - that holding is lost.");
                }
            }
        }

        ConfigurationSection warehouse = node.getConfigurationSection("warehouse");
        if (warehouse != null) {
            for (String key : warehouse.getKeys(false)) {
                Material material = Material.matchMaterial(key);
                if (material == null) {
                    plugin.getLogger().warning(FILE + ": warehouse item '" + key + "' of "
                            + company.name() + " is unknown on this server - dropped.");
                    continue;
                }
                company.addStock(material, warehouse.getLong(key));
            }
        }

        for (Map<?, ?> row : node.getMapList("factories")) {
            Object type = row.get("type");
            if (type == null) {
                continue;
            }
            company.factories().add(new Factory(String.valueOf(type),
                    number(row.get("level")).intValue()));
        }

        List<Double> history = new ArrayList<>();
        for (Object point : node.getList("price-history", List.of())) {
            if (point instanceof Number n) {
                history.add(n.doubleValue());
            }
        }
        company.restorePriceHistory(history, 24);
        return company;
    }

    YamlConfiguration build(CorpService service) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of(
                "RPGCore - 기업 등기부입니다. 플러그인이 계속 다시 씁니다.",
                "공장 종류와 주식 규칙은 corporations.yml 에 있습니다."));
        yaml.set("seeded", service.seededFlag());

        for (Company company : service.companyMap().values()) {
            String path = "companies." + company.id();
            yaml.set(path + ".name", company.name());
            yaml.set(path + ".ticker", company.ticker());
            yaml.set(path + ".ceo", company.ceo() == null ? null : company.ceo().toString());
            yaml.set(path + ".npc", company.npc());
            yaml.set(path + ".created-at", company.createdAt());
            yaml.set(path + ".cash", company.cash());
            yaml.set(path + ".shares", company.sharesIssued());
            yaml.set(path + ".sell-percent", company.sellPercent());
            yaml.set(path + ".auto-buy-inputs", company.autoBuyInputs());
            yaml.set(path + ".dividend-percent", company.dividendPercent());
            yaml.set(path + ".share-price", round(company.sharePrice()));
            yaml.set(path + ".sentiment", round(company.sentiment()));
            yaml.set(path + ".last-revenue", company.lastRevenue());
            yaml.set(path + ".last-costs", company.lastCosts());
            yaml.set(path + ".last-wages", company.lastWages());
            yaml.set(path + ".last-profit", company.lastProfit());
            yaml.set(path + ".lifetime-revenue", company.lifetimeRevenue());
            yaml.set(path + ".lifetime-costs", company.lifetimeCosts());
            yaml.set(path + ".lifetime-dividends", company.lifetimeDividends());
            yaml.set(path + ".days", company.daysOperating());

            for (Map.Entry<UUID, Company.Employee> entry : company.employees().entrySet()) {
                String staff = path + ".employees." + entry.getKey();
                yaml.set(staff + ".name", entry.getValue().name());
                yaml.set(staff + ".role", entry.getValue().role().name());
                yaml.set(staff + ".unpaid-days", entry.getValue().unpaidDays());
                yaml.set(staff + ".joined-at", entry.getValue().joinedAt());
            }
            for (Map.Entry<UUID, Long> entry : company.holders().entrySet()) {
                yaml.set(path + ".holders." + entry.getKey(), entry.getValue());
            }
            for (Map.Entry<Material, Long> entry : company.warehouse().entrySet()) {
                yaml.set(path + ".warehouse." + entry.getKey().getKey().getKey(), entry.getValue());
            }
            List<Map<String, Object>> factories = new ArrayList<>();
            for (Factory factory : company.factories()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("type", factory.typeId());
                row.put("level", factory.level());
                factories.add(row);
            }
            yaml.set(path + ".factories", factories);
            List<Double> history = new ArrayList<>();
            for (double point : company.priceHistory()) {
                history.add(round(point));
            }
            yaml.set(path + ".price-history", history);
        }

        List<Map<String, Object>> offers = new ArrayList<>();
        for (CorpService.Offer offer : service.offerList()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", offer.id().toString());
            row.put("acquirer", offer.acquirer().toString());
            row.put("target", offer.target().toString());
            row.put("premium", round(offer.premium()));
            row.put("price", offer.pricePerShare());
            row.put("total", offer.total());
            row.put("expires", offer.expiresAt());
            offers.add(row);
        }
        yaml.set("offers", offers);
        return yaml;
    }

    private static Number number(Object value) {
        return value instanceof Number n ? n : 0;
    }

    private static double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }
}
