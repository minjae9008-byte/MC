package com.rpgcore.plugin.gui;

import com.rpgcore.plugin.RpgCorePlugin;
import com.rpgcore.plugin.platform.BedrockPlatform;
import com.rpgcore.plugin.util.RpgScoreboard;
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
 * Real, clickable chest-GUI presentation of the same stats the datapack's
 * vanilla-only /trigger rpgcore.menu text menu shows. Purely presentational:
 * every "+" button just fires the matching trigger objective, so the
 * datapack's stats/alloc_*.mcfunction still owns the actual rules.
 *
 * Slot layout (extend STAT_SLOTS to add more allocatable stats):
 *   4  - player head / level+xp summary
 *   10 - STR   11 - DEX   12 - VIT   13 - AGI   14 - LUCK
 *   16 - HP    19 - Weight   22 - unspent points   26 - close
 */
public final class StatsMenu {

    private final RpgCorePlugin plugin;
    private final RpgScoreboard board;
    private final BedrockPlatform bedrockPlatform;

    public record StatSlot(int slot, String objective, String triggerObjective, String label, Material icon) {}

    public static final List<StatSlot> STAT_SLOTS = List.of(
            new StatSlot(10, "rpgcore.str", "rpgcore.alloc_str", "STR", Material.IRON_SWORD),
            new StatSlot(11, "rpgcore.dex", "rpgcore.alloc_dex", "DEX", Material.FEATHER),
            new StatSlot(12, "rpgcore.vit", "rpgcore.alloc_vit", "VIT", Material.GOLDEN_APPLE),
            new StatSlot(13, "rpgcore.agi", "rpgcore.alloc_agi", "AGI", Material.RABBIT_FOOT),
            new StatSlot(14, "rpgcore.luck", "rpgcore.alloc_luck", "LUCK", Material.EMERALD)
    );

    /** Marker holder so StatsMenuListener can reliably recognise this GUI. */
    public static final class Holder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }
    }

    public static final int CLOSE_SLOT = 26;

    public static StatSlot byClickedSlot(int slot) {
        for (StatSlot s : STAT_SLOTS) {
            if (s.slot() == slot) {
                return s;
            }
        }
        return null;
    }

    public StatsMenu(RpgCorePlugin plugin, RpgScoreboard board, BedrockPlatform bedrockPlatform) {
        this.plugin = plugin;
        this.board = board;
        this.bedrockPlatform = bedrockPlatform;
    }

    public void open(Player player) {
        String title = ChatColor.translateAlternateColorCodes('&',
                plugin.getConfig().getString("gui.title", "&8캐릭터 정보"));
        int size = plugin.getConfig().getInt("gui.size", 27);
        Holder holder = new Holder();
        Inventory inv = plugin.getServer().createInventory(holder, size, title);
        holder.setInventory(inv);

        inv.setItem(4, buildHeadItem(player));

        for (StatSlot s : STAT_SLOTS) {
            inv.setItem(s.slot(), buildStatItem(player, s));
        }

        inv.setItem(16, buildInfoItem(Material.REDSTONE,
                ChatColor.RED + "HP",
                ChatColor.GRAY + "" + board.get(player, "rpgcore.hp") + " / " + board.get(player, "rpgcore.hp_max")));

        int weight = board.get(player, "rpgcore.weight");
        int weightMax = board.get(player, "rpgcore.weight_max");
        int tier = board.get(player, "rpgcore.weight_tier");
        inv.setItem(19, buildInfoItem(Material.ANVIL,
                ChatColor.AQUA + "무게 (Weight)",
                ChatColor.GRAY + "" + weight + " / " + weightMax,
                ChatColor.GRAY + "부담 단계: " + tier + " / 3"));

        inv.setItem(22, buildInfoItem(Material.NETHER_STAR,
                ChatColor.GOLD + "남은 스탯 포인트",
                ChatColor.YELLOW + String.valueOf(board.get(player, "rpgcore.points"))));

        // Bedrock renders barrier blocks inconsistently through Geyser, so the
        // close button uses a pane, which exists identically on both platforms.
        inv.setItem(CLOSE_SLOT, buildInfoItem(Material.RED_STAINED_GLASS_PANE, ChatColor.RED + "닫기"));

        player.openInventory(inv);
    }

    private ItemStack buildHeadItem(Player player) {
        List<String> lore = List.of(
                ChatColor.YELLOW + "Lv. " + board.get(player, "rpgcore.level"),
                ChatColor.GREEN + "XP " + board.get(player, "rpgcore.xp") + " / " + board.get(player, "rpgcore.xp_need")
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

    private ItemStack buildStatItem(Player player, StatSlot s) {
        ItemStack item = new ItemStack(s.icon());
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.AQUA + s.label() + ChatColor.GRAY + ": " + ChatColor.WHITE + board.get(player, s.objective()));
        meta.setLore(List.of(
                ChatColor.GREEN + "클릭하여 포인트 1개 사용 (+1 " + s.label() + ")",
                ChatColor.DARK_GRAY + s.triggerObjective()
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
