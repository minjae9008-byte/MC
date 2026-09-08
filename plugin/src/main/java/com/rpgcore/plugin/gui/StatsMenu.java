package com.rpgcore.plugin.gui;

import com.rpgcore.plugin.RpgCorePlugin;
import com.rpgcore.plugin.data.PlayerData;
import com.rpgcore.plugin.platform.BedrockPlatform;
import com.rpgcore.plugin.stats.StatType;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.List;

/**
 * Chest GUI for Java players (Geyser also translates it for Bedrock as a
 * fallback). Clicking a stat calls StatsService directly - the old build went
 * through /trigger because the datapack owned the rules; the plugin owns them
 * now, so the round-trip is gone.
 *
 * Slots: 4 head/level, 10-14 stats (from StatType order), 16 HP, 19 weight,
 * 22 unspent points, and the last slot of the inventory closes the menu.
 */
public final class StatsMenu {

    private static final int FIRST_STAT_SLOT = 10;
    /**
     * The fixed slots above need three rows; gui.size is clamped to a legal
     * chest size (a multiple of 9, at most six rows) that is at least this
     * big, so a mistyped config can never throw on setItem.
     */
    private static final int MIN_SIZE = 27;
    private static final int MAX_SIZE = 54;

    private final RpgCorePlugin plugin;
    private final BedrockPlatform bedrockPlatform;

    /** Marker holder so StatsMenuListener can reliably recognise this GUI. */
    public static final class Holder implements InventoryHolder {
        private Inventory inventory;
        private int closeSlot;

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        /** Slot of the close button, which follows the configured gui.size. */
        public int closeSlot() {
            return closeSlot;
        }

        void setCloseSlot(int closeSlot) {
            this.closeSlot = closeSlot;
        }
    }

    public StatsMenu(RpgCorePlugin plugin, BedrockPlatform bedrockPlatform) {
        this.plugin = plugin;
        this.bedrockPlatform = bedrockPlatform;
    }

    public static int slotOf(StatType type) {
        return FIRST_STAT_SLOT + type.ordinal();
    }

    public static StatType statAt(int slot) {
        int index = slot - FIRST_STAT_SLOT;
        StatType[] values = StatType.values();
        return index >= 0 && index < values.length ? values[index] : null;
    }

    public void open(Player player) {
        PlayerData data = plugin.players().get(player);

        String title = ChatColor.translateAlternateColorCodes('&',
                plugin.getConfig().getString("gui.title", "&8캐릭터 정보"));
        int size = clampSize(plugin.getConfig().getInt("gui.size", MIN_SIZE));
        Holder holder = new Holder();
        Inventory inv = plugin.getServer().createInventory(holder, size, title);
        holder.setInventory(inv);
        holder.setCloseSlot(size - 1);

        inv.setItem(4, buildHeadItem(player, data));

        for (StatType type : StatType.values()) {
            inv.setItem(slotOf(type), buildStatItem(data, type));
        }

        inv.setItem(16, buildInfoItem(Material.REDSTONE,
                ChatColor.RED + "HP",
                ChatColor.GRAY + "" + (int) Math.ceil(player.getHealth()) + " / " + data.maxHealth()));

        inv.setItem(19, buildInfoItem(Material.ANVIL,
                ChatColor.AQUA + "무게 (Weight)",
                ChatColor.GRAY + "" + data.weight() + " / " + data.weightMax()
                        + "  (" + data.loadPercent() + "%)",
                ChatColor.GRAY + "부담 단계: " + data.weightTier() + " / 3"));

        inv.setItem(22, buildInfoItem(Material.NETHER_STAR,
                ChatColor.GOLD + "남은 스탯 포인트",
                ChatColor.YELLOW + String.valueOf(data.points())));

        // Bedrock renders barrier blocks inconsistently through Geyser, so the
        // close button uses a pane, which exists identically on both platforms.
        inv.setItem(holder.closeSlot(), buildInfoItem(Material.RED_STAINED_GLASS_PANE, ChatColor.RED + "닫기"));

        player.openInventory(inv);
    }

    /** Rounds gui.size to a legal chest size inside [MIN_SIZE, MAX_SIZE]. */
    private static int clampSize(int configured) {
        int rounded = ((configured + 8) / 9) * 9;
        return Math.min(MAX_SIZE, Math.max(MIN_SIZE, rounded));
    }

    private ItemStack buildHeadItem(Player player, PlayerData data) {
        List<String> lore = List.of(
                ChatColor.YELLOW + "Lv. " + data.level(),
                ChatColor.GREEN + "XP " + data.xp() + " / " + data.xpNeed()
        );

        // Player-head skins resolve through Floodgate for Bedrock players and
        // can end up blank, so give them a plain icon instead.
        if (bedrockPlatform.isBedrockPlayer(player)) {
            ItemStack item = new ItemStack(Material.BOOK);
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName(ChatColor.GOLD + player.getName());
            meta.setLore(lore);
            item.setItemMeta(meta);
            return item;
        }

        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.setOwningPlayer(player);
        meta.setDisplayName(ChatColor.GOLD + player.getName());
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildStatItem(PlayerData data, StatType type) {
        ItemStack item = new ItemStack(type.icon());
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.AQUA + type.label() + ChatColor.GRAY + ": "
                + ChatColor.WHITE + data.stat(type));
        meta.setLore(List.of(
                ChatColor.GRAY + type.description(),
                ChatColor.GREEN + "클릭하여 포인트 1개 사용 (+1 " + type.label() + ")"
        ));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildInfoItem(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        if (lore.length > 0) {
            meta.setLore(List.of(lore));
        }
        item.setItemMeta(meta);
        return item;
    }
}
