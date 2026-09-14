package com.rpgcore.plugin.guild;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

/**
 * Marks an inventory as a guild's vault, and says whose.
 *
 * Deliberately not registered as one of RPGCore's menus: the menu listener
 * cancels every click in those, and a vault is a chest - taking things out of
 * it is the entire point. What it does need is an identity, so the close
 * handler knows which guild to write back.
 */
public final class GuildVaultHolder implements InventoryHolder {

    private final UUID guildId;
    private Inventory inventory;

    GuildVaultHolder(UUID guildId) {
        this.guildId = guildId;
    }

    public UUID guildId() {
        return guildId;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }
}
