package com.rpgcore.plugin.job;

import com.rpgcore.plugin.RpgCorePlugin;
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
 * Job picker. Like the stats GUI it is a plain chest inventory so Java and
 * Bedrock players see the same screen.
 */
public final class JobMenu {

    /** Marker holder, and the slot -> job mapping for the click handler. */
    public static final class Holder implements InventoryHolder {
        private Inventory inventory;
        private final List<RpgJob> slots = new ArrayList<>();

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        public RpgJob jobAt(int slot) {
            return slot >= 0 && slot < slots.size() ? slots.get(slot) : null;
        }
    }

    private final RpgCorePlugin plugin;

    public JobMenu(RpgCorePlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        List<RpgJob> jobs = plugin.jobs().jobs();
        if (jobs.isEmpty()) {
            player.sendMessage(ChatColor.RED + "[RPGCore] 선택할 수 있는 직업이 없습니다.");
            return;
        }

        int size = Math.min(54, ((jobs.size() + 8) / 9) * 9);
        Holder holder = new Holder();
        Inventory inv = plugin.getServer().createInventory(holder, size,
                ChatColor.translateAlternateColorCodes('&',
                        plugin.jobs().menuTitle()));
        holder.setInventory(inv);

        RpgJob current = plugin.jobs().of(player);
        int level = plugin.players().get(player).level();

        for (int i = 0; i < jobs.size() && i < size; i++) {
            RpgJob job = jobs.get(i);
            holder.slots.add(job);
            inv.setItem(i, buildItem(job, job.equals(current), level >= job.minLevel()));
        }
        player.openInventory(inv);
    }

    private ItemStack buildItem(RpgJob job, boolean current, boolean unlocked) {
        ItemStack item = new ItemStack(unlocked ? job.icon() : Material.GRAY_DYE);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(job.displayName() + (current ? ChatColor.GREEN + "  (현재 직업)" : ""));

        List<String> lore = new ArrayList<>(plugin.jobs().describe(job));
        lore.add("");
        if (current) {
            lore.add(ChatColor.GREEN + "이미 선택한 직업입니다.");
        } else if (!unlocked) {
            lore.add(ChatColor.RED + "레벨이 부족합니다.");
        } else {
            int cost = plugin.jobs().changeCostLevels();
            lore.add(ChatColor.YELLOW + "클릭하여 선택"
                    + (cost > 0 ? ChatColor.GRAY + " (경험치 레벨 " + cost + " 소모)" : ""));
        }
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }
}
