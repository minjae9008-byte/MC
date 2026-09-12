package com.rpgcore.plugin.progress;

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
 * Title picker. Earned titles first and in colour, locked ones grey with what
 * they take - so the screen doubles as the list of what there is to chase.
 */
public final class TitleMenu {

    public static final class Holder implements InventoryHolder {
        private Inventory inventory;
        /** Slot -> title id; the "no title" button maps to an empty string. */
        private final Map<Integer, String> slots = new HashMap<>();

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        /** Null for furniture, "" for the clear button, else the title id. */
        public String titleAt(int slot) {
            return slots.get(slot);
        }
    }

    private final RpgCorePlugin plugin;

    public TitleMenu(RpgCorePlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        List<TitleService.Title> titles = plugin.titles().ordered(player);
        if (titles.isEmpty()) {
            player.sendMessage(ChatColor.RED + "[칭호] 이 서버에는 칭호가 없습니다.");
            return;
        }

        Holder holder = new Holder();
        int rows = Math.min(5, (titles.size() + 8) / 9);
        Inventory inv = plugin.getServer().createInventory(holder, rows * 9 + 9,
                ChatColor.translateAlternateColorCodes('&', plugin.rpgConfig().titleMenuTitle()));
        holder.setInventory(inv);

        TitleService.Title worn = plugin.titles().worn(player);
        for (int i = 0; i < titles.size() && i < rows * 9; i++) {
            TitleService.Title title = titles.get(i);
            holder.slots.put(i, title.id());
            inv.setItem(i, icon(player, title, title.equals(worn)));
        }

        int clearSlot = rows * 9 + 4;
        holder.slots.put(clearSlot, "");
        inv.setItem(clearSlot, button(Material.BARRIER, ChatColor.RED + "칭호 떼기",
                List.of(ChatColor.GRAY + "이름 옆 칭호를 표시하지 않습니다.")));
        player.openInventory(inv);
    }

    private ItemStack icon(Player player, TitleService.Title title, boolean worn) {
        boolean earned = plugin.titles().hasEarned(player, title.id());
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "조건: " + title.requirement());
        lore.add("");
        if (worn) {
            lore.add(ChatColor.GREEN + "지금 착용 중입니다.");
        } else if (earned) {
            lore.add(ChatColor.YELLOW + "클릭하여 착용");
        } else {
            lore.add(ChatColor.DARK_GRAY + "아직 얻지 못했습니다.");
        }
        return button(earned ? title.icon() : Material.GRAY_DYE,
                (earned ? title.display() : ChatColor.DARK_GRAY + ChatColor.stripColor(title.display()))
                        + (worn ? ChatColor.GREEN + "  (착용 중)" : ""),
                lore);
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
