package com.rpgcore.plugin.guild;

import com.rpgcore.plugin.RpgCorePlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;

/**
 * Writes a guild vault back to disk when somebody closes it.
 *
 * The vault is a live inventory that any member can change at any time, and
 * the only moments at which it is guaranteed to be in a consistent state a
 * player would recognise are "someone just finished with it" and "the server
 * is going down". Saving on close means the worst a crash can cost is the
 * moves made by whoever still had it open - not the whole vault.
 */
public final class GuildVaultListener implements Listener {

    private final RpgCorePlugin plugin;

    public GuildVaultListener(RpgCorePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof GuildVaultHolder holder) {
            plugin.guilds().handleVaultClosed(holder.guildId());
        }
    }
}
