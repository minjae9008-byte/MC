package com.rpgcore.plugin.economy;

import com.rpgcore.plugin.RpgCorePlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads and writes market.yml - what the market currently holds and at what
 * price, as opposed to economy.yml, which is how it works.
 *
 * Deliberately two files. The catalogue is the operator's and is edited by
 * hand; this one is the plugin's and is rewritten constantly. Mixing them
 * means every price tick rewrites the operator's comments, and one bad write
 * costs them their configuration rather than a day of price history.
 *
 * A state file that will not parse is not fatal: the market rebuilds itself
 * from the catalogue at its base prices, which is a fresh economy rather than
 * a dead one.
 */
final class MarketStorage {

    static final String FILE = "market.yml";

    private final RpgCorePlugin plugin;
    private final File file;

    MarketStorage(RpgCorePlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), FILE);
    }

    /** Pours the saved state back into an already-built market. */
    void restore(MarketService market) {
        if (!file.isFile()) {
            // First run: the reserve is stocked so the central bank has
            // something to intervene with on day one.
            market.seedReserve(30);
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        market.restoreTreasury(
                yaml.getLong("treasury", market.treasury()),
                yaml.getLong("printed", 0),
                yaml.getLong("tax-take", 0));
        market.restorePriceLevel(yaml.getDouble("price-level", 1.0));

        ConfigurationSection root = yaml.getConfigurationSection("items");
        if (root != null) {
            for (String id : root.getKeys(false)) {
                ConfigurationSection node = root.getConfigurationSection(id);
                MarketItem item = market.items().get(id);
                if (node == null || item == null) {
                    // A good the operator has since removed from the
                    // catalogue. Its saved stock goes with it.
                    continue;
                }
                item.stock(node.getDouble("stock", item.baseStock()));
                item.sentiment(node.getDouble("sentiment", 1.0));
                item.price(node.getDouble("price", item.mid()));
                item.previousClose(node.getDouble("previous-close", item.mid()));
                item.closingStock(node.getDouble("closing-stock", -1));
                item.restoreVolume(node.getLong("lifetime-bought", 0), node.getLong("lifetime-sold", 0));
                item.shockNote(node.getString("shock-note"));
                List<Double> history = new ArrayList<>();
                for (Object point : node.getList("history", List.of())) {
                    if (point instanceof Number number) {
                        history.add(number.doubleValue());
                    }
                }
                item.restoreHistory(history, market.config().historyPoints());
                market.restoreReserve(id, node.getDouble("reserve", 0));
            }
        }
        // Goods added to the catalogue after the last save have no reserve
        // line of their own; this stocks only those.
        market.seedReserve(30);

        for (Map<?, ?> row : yaml.getMapList("shocks")) {
            Object item = row.get("item");
            Object text = row.get("text");
            if (item == null || text == null) {
                continue;
            }
            market.restoreShock(new MarketService.Shock(
                    intOf(row.get("day")), String.valueOf(item), String.valueOf(text),
                    doubleOf(row.get("magnitude"))));
        }
        plugin.getLogger().info("Market state restored from " + FILE + ".");
    }

    /** Assembles the snapshot on the main thread; the dump happens off it. */
    YamlConfiguration build(MarketService market) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of(
                "RPGCore - 시장 상태입니다. 플러그인이 계속 다시 씁니다.",
                "설정(품목표, 임금, 금리 등)은 economy.yml 에 있습니다."));
        yaml.set("treasury", market.treasury());
        yaml.set("printed", market.printed());
        yaml.set("tax-take", market.taxTake());
        yaml.set("price-level", round(market.priceLevel()));

        Map<String, Double> reserve = market.reserveView();
        for (MarketItem item : market.items().values()) {
            String path = "items." + item.id();
            yaml.set(path + ".stock", round(item.stock()));
            yaml.set(path + ".sentiment", round(item.sentiment()));
            yaml.set(path + ".price", round(item.mid()));
            yaml.set(path + ".previous-close", round(item.previousClose()));
            yaml.set(path + ".closing-stock", round(item.closingStock()));
            yaml.set(path + ".lifetime-bought", item.lifetimeBought());
            yaml.set(path + ".lifetime-sold", item.lifetimeSold());
            yaml.set(path + ".reserve", round(reserve.getOrDefault(item.id(), 0.0)));
            if (item.shockNote() != null) {
                yaml.set(path + ".shock-note", item.shockNote());
            }
            List<Double> history = new ArrayList<>();
            for (double point : item.history()) {
                history.add(round(point));
            }
            yaml.set(path + ".history", history);
        }

        List<Map<String, Object>> shocks = new ArrayList<>();
        for (MarketService.Shock shock : market.shocks()) {
            // Ordered, so the file only changes when the data does.
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("day", shock.day());
            row.put("item", shock.item());
            row.put("text", shock.text());
            row.put("magnitude", round(shock.magnitude()));
            shocks.add(row);
        }
        yaml.set("shocks", shocks);
        return yaml;
    }

    /** Four decimals: enough for a price, short enough to keep the file small. */
    private static double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    private static int intOf(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static double doubleOf(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0;
    }
}
