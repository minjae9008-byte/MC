package com.rpgcore.plugin.gui;

import com.rpgcore.plugin.RpgCorePlugin;
import com.rpgcore.plugin.anvil.AnvilRecipe;
import com.rpgcore.plugin.job.RpgJob;
import com.rpgcore.plugin.data.PlayerData;
import com.rpgcore.plugin.stats.StatType;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * The stats GUI, identical on every platform: a plain chest inventory, which
 * Geyser translates into a native Bedrock container screen on its own. Every
 * icon is an ordinary block or item that renders the same in both clients, so
 * there is no second UI path to keep in sync.
 *
 * Slots: 0 job, 4 player card, 8 anvil recipes, 10-14 stats (from StatType
 * order), 16 HP, 19 weight, 22 unspent points, 25 gear condition, and the last
 * slot of the inventory closes the menu.
 */
public final class StatsMenu {

    private static final int FIRST_STAT_SLOT = 10;
    /** Opens the job picker. */
    public static final int JOB_SLOT = 0;
    /**
     * The fixed slots above need three rows; gui.size is clamped to a legal
     * chest size (a multiple of 9, at most six rows) that is at least this
     * big, so a mistyped config can never throw on setItem.
     */
    private static final int MIN_SIZE = 27;
    private static final int MAX_SIZE = 54;

    private final RpgCorePlugin plugin;

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

    public StatsMenu(RpgCorePlugin plugin) {
        this.plugin = plugin;
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

        String title = ChatColor.translateAlternateColorCodes('&', plugin.rpgConfig().guiTitle());
        int size = clampSize(plugin.rpgConfig().guiSize());
        Holder holder = new Holder();
        Inventory inv = plugin.getServer().createInventory(holder, size, title);
        holder.setInventory(inv);
        holder.setCloseSlot(size - 1);

        inv.setItem(JOB_SLOT, buildJobItem(player));
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

        inv.setItem(25, buildGearItem(data));
        inv.setItem(8, buildAnvilGuideItem());

        // Bedrock renders barrier blocks inconsistently through Geyser, so the
        // close button uses a pane, which exists identically on both platforms.
        inv.setItem(holder.closeSlot(), buildInfoItem(Material.RED_STAINED_GLASS_PANE, ChatColor.RED + "닫기"));

        player.openInventory(inv);
    }

    /**
     * Condition of the held weapon and the worn armour, with the performance
     * each currently yields - the number that actually multiplies attack
     * damage, armour and mining speed.
     */
    private ItemStack buildGearItem(PlayerData data) {
        int weaponPerformance = plugin.gear().performancePercent(data.weaponCondition());
        int armorPerformance = plugin.gear().performancePercent(data.armorCondition());
        return buildInfoItem(Material.GRINDSTONE,
                ChatColor.LIGHT_PURPLE + "장비 상태",
                ChatColor.GRAY + "무기 내구도 " + ChatColor.WHITE + data.weaponCondition() + "%"
                        + ChatColor.GRAY + " -> 공격력/채굴 " + performanceColor(weaponPerformance)
                        + weaponPerformance + "%",
                ChatColor.GRAY + "방어구 내구도 " + ChatColor.WHITE + data.armorCondition() + "%"
                        + ChatColor.GRAY + " -> 방어력 " + performanceColor(armorPerformance)
                        + armorPerformance + "%",
                ChatColor.DARK_GRAY + "모루에서 수리하면 성능도 함께 돌아옵니다.");
    }

    /** The anvil recipes, so the mechanic is discoverable without a wiki. */
    private ItemStack buildAnvilGuideItem() {
        List<String> lore = new ArrayList<>();
        if (plugin.anvil().isEmpty()) {
            lore.add(ChatColor.GRAY + "등록된 조합법이 없습니다.");
        } else {
            lore.add(ChatColor.GRAY + "왼쪽 칸에 장비, 오른쪽 칸에 재료:");
            for (AnvilRecipe recipe : plugin.anvil().recipes()) {
                lore.add("  " + plugin.anvil().describe(recipe));
            }
        }
        return buildInfoItem(Material.ANVIL, ChatColor.GOLD + "모루 강화", lore.toArray(new String[0]));
    }

    private static ChatColor performanceColor(int performance) {
        if (performance >= 100) {
            return ChatColor.GREEN;
        }
        return performance >= 75 ? ChatColor.YELLOW : ChatColor.RED;
    }

    /** Rounds gui.size to a legal chest size inside [MIN_SIZE, MAX_SIZE]. */
    private static int clampSize(int configured) {
        int rounded = ((configured + 8) / 9) * 9;
        return Math.min(MAX_SIZE, Math.max(MIN_SIZE, rounded));
    }

    /**
     * Player card. A written book, not a player head: head skins are fetched
     * per platform and come out blank for Bedrock players often enough that
     * one shared icon is simply better than two code paths.
     */
    private ItemStack buildHeadItem(Player player, PlayerData data) {
        RpgJob job = plugin.jobs().byId(data.jobId());
        return buildInfoItem(Material.WRITABLE_BOOK,
                ChatColor.GOLD + player.getName(),
                ChatColor.YELLOW + "Lv. " + data.level(),
                ChatColor.GREEN + "XP " + data.xp() + " / " + data.xpNeed(),
                ChatColor.GRAY + "직업: " + (job == null ? ChatColor.DARK_GRAY + "없음" : job.displayName()));
    }

    /** Job button; also the only hint that jobs exist, so it reads as a button. */
    private ItemStack buildJobItem(Player player) {
        RpgJob job = plugin.jobs().of(player);
        List<String> lore = new ArrayList<>();
        if (job == null) {
            lore.add(ChatColor.GRAY + "아직 직업이 없습니다.");
        } else {
            lore.addAll(plugin.jobs().describe(job));
        }
        lore.add("");
        lore.add(ChatColor.YELLOW + "클릭하여 직업 선택 창 열기");
        return buildInfoItem(job == null ? Material.WOODEN_SWORD : job.icon(),
                ChatColor.LIGHT_PURPLE + "직업"
                        + (job == null ? "" : ChatColor.GRAY + " - " + job.displayName()),
                lore.toArray(new String[0]));
    }

    private ItemStack buildStatItem(PlayerData data, StatType type) {
        ItemStack item = new ItemStack(type.icon());
        ItemMeta meta = item.getItemMeta();
        int own = data.stat(type);
        int total = plugin.stats().effectiveStat(data, type);
        meta.setDisplayName(ChatColor.AQUA + type.label() + ChatColor.GRAY + ": "
                + ChatColor.WHITE + total
                + (total != own ? ChatColor.GRAY + " (" + own + " +" + (total - own) + " 직업)" : ""));
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
