package com.rpgcore.plugin.auction;

import com.rpgcore.plugin.RpgCorePlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Reads and writes auctions.yml.
 *
 * A live lot holds two things that belong to real people - the seller's item
 * and the top bidder's gold - so this file is not a cache that can be thrown
 * away. Every change marks it stale and it is written within a second, off
 * the main thread; a lot that cannot be read back is reported loudly rather
 * than skipped quietly, because a skipped lot is a player's item disappearing.
 */
final class AuctionStorage {

    private final RpgCorePlugin plugin;
    private final File file;

    AuctionStorage(RpgCorePlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "auctions.yml");
    }

    List<AuctionListing> load() {
        List<AuctionListing> listings = new ArrayList<>();
        if (!file.isFile()) {
            return listings;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("listings");
        if (root == null) {
            return listings;
        }

        for (String key : root.getKeys(false)) {
            ConfigurationSection node = root.getConfigurationSection(key);
            if (node == null) {
                continue;
            }
            try {
                ItemStack item = node.getItemStack("item");
                if (item == null || item.getType().isAir()) {
                    plugin.getLogger().severe("auctions.yml: lot " + key + " has no item - skipped."
                            + " Its seller will need it returned by hand.");
                    continue;
                }
                AuctionListing listing = new AuctionListing(
                        UUID.fromString(key),
                        UUID.fromString(node.getString("seller", "")),
                        node.getString("seller-name", "?"),
                        item,
                        node.getInt("start-price", 1),
                        node.getInt("buy-now-price", 0),
                        node.getLong("created-at", System.currentTimeMillis()),
                        node.getLong("ends-at", System.currentTimeMillis()));
                String bidder = node.getString("top-bidder");
                int topBid = node.getInt("top-bid", 0);
                if (bidder != null && topBid > 0) {
                    listing.restoreBid(UUID.fromString(bidder), node.getString("top-bidder-name", "?"), topBid);
                }
                listings.add(listing);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().severe("auctions.yml: lot " + key + " is malformed (" + e.getMessage()
                        + ") - skipped. Its seller will need it returned by hand.");
            }
        }
        return listings;
    }

    /** Assembles the book in memory; the YAML dump happens off the main thread. */
    YamlConfiguration build(Collection<AuctionListing> listings) {
        YamlConfiguration yaml = new YamlConfiguration();
        for (AuctionListing listing : listings) {
            String path = "listings." + listing.id();
            yaml.set(path + ".seller", listing.seller().toString());
            yaml.set(path + ".seller-name", listing.sellerName());
            // Cloned: the writer thread turns this into YAML later, and the
            // lot's own stack must not be reachable from two threads at once.
            yaml.set(path + ".item", listing.item().clone());
            yaml.set(path + ".start-price", listing.startPrice());
            yaml.set(path + ".buy-now-price", listing.buyNowPrice());
            yaml.set(path + ".created-at", listing.createdAtMs());
            yaml.set(path + ".ends-at", listing.endsAtMs());
            if (listing.hasBids()) {
                yaml.set(path + ".top-bidder", listing.topBidder().toString());
                yaml.set(path + ".top-bidder-name", listing.topBidderName());
                yaml.set(path + ".top-bid", listing.topBid());
            }
        }
        return yaml;
    }
}
