package com.rpgcore.plugin.anvil;

import com.rpgcore.plugin.RpgCorePlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.view.AnvilView;

/**
 * Bridges the custom recipes into the vanilla anvil.
 *
 * Only combinations that match a recipe are touched; anything else - vanilla
 * repair, combining two enchanted items, plain renaming - is left exactly as
 * the server computed it. Once the result is set, vanilla's own anvil handles
 * taking it: it charges the levels and consumes both slots, using the repair
 * cost and item count we set here.
 */
public final class AnvilListener implements Listener {

    private final RpgCorePlugin plugin;
    private final AnvilService service;

    public AnvilListener(RpgCorePlugin plugin, AnvilService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPrepare(PrepareAnvilEvent event) {
        if (service.isEmpty()) {
            return;
        }
        AnvilInventory inventory = event.getInventory();
        ItemStack target = inventory.getFirstItem();
        ItemStack ingredient = inventory.getSecondItem();

        AnvilRecipe recipe = service.match(target, ingredient);
        if (recipe == null) {
            return;
        }

        ItemStack result = service.buildResult(target, recipe);
        if (result == null) {
            // The recipe matched but would change nothing - already at full
            // durability, or the enchantment is capped. Leave the anvil empty
            // rather than charging levels for a no-op, which is also what
            // vanilla does when you try to repair an undamaged item.
            event.setResult(null);
            return;
        }

        applyRenameIfAny(event.getView(), result);
        event.setResult(result);

        AnvilView view = event.getView();
        view.setRepairCost(recipe.levelCost());
        view.setRepairItemCountCost(recipe.ingredientAmount());
        // Vanilla refuses results whose enchantments exceed their normal
        // maximum; recipes are allowed past it, so that check is waived for
        // the combinations we produce.
        view.bypassEnchantmentLevelRestriction(true);

        // The client is told the cost with the window update, which Paper has
        // already sent by the time we change it, so nudge it next tick.
        if (event.getView().getPlayer() instanceof Player player) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    player.updateInventory();
                }
            });
        }
    }

    /** Keeps the anvil's rename box working on top of a custom recipe. */
    private void applyRenameIfAny(AnvilView view, ItemStack result) {
        String rename = view.getRenameText();
        if (rename == null || rename.isBlank()) {
            return;
        }
        ItemMeta meta = result.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(rename);
            result.setItemMeta(meta);
        }
    }
}
