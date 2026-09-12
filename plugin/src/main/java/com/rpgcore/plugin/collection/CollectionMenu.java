package com.rpgcore.plugin.collection;

import com.rpgcore.plugin.RpgCorePlugin;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The collection log, as two plain chest screens: the category list, and one
 * page per category. A found entry shows the item itself; a missing one shows
 * grey glass, so a page reads as a progress bar at a glance.
 */
public final class CollectionMenu {

    /** Marker holder plus the slot mapping the click handler needs. */
    public static final class Holder implements InventoryHolder {
        private Inventory inventory;
        /** Slot -> category to open; empty on a category page. */
        private final Map<Integer, String> links = new HashMap<>();
        private int backSlot = -1;

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        public String linkAt(int slot) {
            return links.get(slot);
        }

        public int backSlot() {
            return backSlot;
        }
    }

    private static final int PAGE_SIZE = 45;

    private final RpgCorePlugin plugin;

    public CollectionMenu(RpgCorePlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        List<CollectionCategory> categories = plugin.collections().categories();
        if (categories.isEmpty()) {
            player.sendMessage(ChatColor.RED + "[도감] 등록된 도감이 없습니다.");
            return;
        }

        Holder holder = new Holder();
        int size = Math.min(54, Math.max(9, ((categories.size() + 8) / 9) * 9));
        Inventory inv = plugin.getServer().createInventory(holder, size,
                ChatColor.translateAlternateColorCodes('&', plugin.rpgConfig().collectionTitle()));
        holder.setInventory(inv);

        for (int i = 0; i < categories.size() && i < size; i++) {
            CollectionCategory category = categories.get(i);
            holder.links.put(i, category.id());
            inv.setItem(i, categoryIcon(player, category));
        }
        player.openInventory(inv);
    }

    public void openCategory(Player player, String id) {
        CollectionCategory category = plugin.collections().byId(id);
        if (category == null) {
            open(player);
            return;
        }

        Holder holder = new Holder();
        // One screen per page keeps this a single chest on Bedrock too; a
        // category longer than 45 entries is simply cut, and the load warning
        // in the log is the place that says so.
        Inventory inv = plugin.getServer().createInventory(holder, 54,
                ChatColor.stripColor(category.display()));
        holder.setInventory(inv);

        List<Material> entries = category.entries();
        for (int i = 0; i < entries.size() && i < PAGE_SIZE; i++) {
            inv.setItem(i, entryIcon(player, entries.get(i)));
        }
        holder.backSlot = 49;
        inv.setItem(49, button(Material.ARROW, ChatColor.YELLOW + "← 도감 목록",
                List.of(ChatColor.GRAY + "수집 " + plugin.collections().countIn(player, category)
                        + "/" + category.size())));
        player.openInventory(inv);
    }

    private ItemStack categoryIcon(Player player, CollectionCategory category) {
        int have = plugin.collections().countIn(player, category);
        boolean done = have >= category.size();
        List<String> lore = new ArrayList<>();
        lore.add((done ? ChatColor.GREEN : ChatColor.GRAY) + "수집 " + have + "/" + category.size());
        lore.add(bar(have, category.size()));
        if (category.grantsTitle()) {
            lore.add("");
            lore.add(ChatColor.GRAY + "완성 보상: " + reward(category));
        }
        lore.add("");
        lore.add(ChatColor.YELLOW + "클릭하여 열기");
        return button(done ? category.icon() : Material.GRAY_DYE,
                category.display() + (done ? ChatColor.GREEN + "  ✔" : ""), lore);
    }

    private String reward(CollectionCategory category) {
        List<String> parts = new ArrayList<>();
        if (category.grantsTitle()) {
            parts.add(ChatColor.translateAlternateColorCodes('&', category.title()));
        }
        if (category.rewardGold() > 0) {
            parts.add(plugin.economy().format(category.rewardGold()));
        }
        if (category.rewardXp() > 0) {
            parts.add(ChatColor.GREEN + "XP " + category.rewardXp());
        }
        return String.join(ChatColor.GRAY + ", ", parts);
    }

    private ItemStack entryIcon(Player player, Material material) {
        boolean have = plugin.collections().has(player, material);
        return button(have ? material : Material.GRAY_STAINED_GLASS_PANE,
                (have ? ChatColor.WHITE : ChatColor.DARK_GRAY) + CollectionService.name(material),
                List.of(have ? ChatColor.GREEN + "수집 완료" : ChatColor.DARK_GRAY + "미수집"));
    }

    /** Ten cells of solid/hollow, which renders identically on Bedrock. */
    private String bar(int have, int total) {
        int filled = total <= 0 ? 10 : (int) Math.round(have * 10.0D / total);
        return ChatColor.GREEN + "■".repeat(filled) + ChatColor.DARK_GRAY + "■".repeat(10 - filled);
    }

    private ItemStack button(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }
}
