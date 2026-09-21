package com.rpgcore.plugin.blueprint;

import com.rpgcore.plugin.RpgCorePlugin;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/**
 * The selection wand.
 *
 * Marked with persistent data rather than by material or name, so a player
 * cannot turn an ordinary hoe into one by renaming it, and so the operator
 * can change which item it is without invalidating the ones already handed
 * out. Left click sets one corner, right click the other - the convention
 * every builder on a Minecraft server already knows.
 *
 * Both clicks are cancelled, which is what stops the wand breaking the block
 * it is selecting.
 */
public final class BlueprintListener implements Listener {

    private final RpgCorePlugin plugin;
    private final NamespacedKey key;

    public BlueprintListener(RpgCorePlugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "blueprint_wand");
    }

    /** The wand, built fresh. */
    public ItemStack wand() {
        Material material = plugin.rpgConfig().blueprintWand();
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(ChatColor.AQUA + "설계 지팡이");
        meta.setLore(List.of(
                ChatColor.GRAY + "좌클릭 - 1번 모서리",
                ChatColor.GRAY + "우클릭 - 2번 모서리",
                "",
                ChatColor.GRAY + "두 모서리를 찍고 " + ChatColor.YELLOW + "/blueprint save <이름>"));
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        stack.setItemMeta(meta);
        return stack;
    }

    public boolean isWand(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return false;
        }
        return stack.getItemMeta().getPersistentDataContainer()
                .has(key, PersistentDataType.BYTE);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null || event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) {
            return;
        }
        if (!plugin.rpgConfig().blueprintEnabled() || !isWand(event.getItem())) {
            return;
        }
        Action action = event.getAction();
        if (action == Action.LEFT_CLICK_BLOCK) {
            event.setCancelled(true);
            plugin.blueprints().setCorner(event.getPlayer(), true,
                    event.getClickedBlock().getLocation());
        } else if (action == Action.RIGHT_CLICK_BLOCK) {
            event.setCancelled(true);
            plugin.blueprints().setCorner(event.getPlayer(), false,
                    event.getClickedBlock().getLocation());
        }
    }
}
