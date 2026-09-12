package com.rpgcore.plugin.progress;

import com.rpgcore.plugin.RpgCorePlugin;
import com.rpgcore.plugin.data.PlayerData;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

/**
 * Gold: what achievements pay out and what duels are wagered with.
 *
 * Deliberately the smallest currency that does the job - a single integer on
 * the scoreboard mirror, so it persists with the world, needs no economy
 * plugin, and an admin can read or set it with plain vanilla commands.
 */
public final class EconomyService {

    private final RpgCorePlugin plugin;

    public EconomyService(RpgCorePlugin plugin) {
        this.plugin = plugin;
    }

    public int balance(Player player) {
        PlayerData data = plugin.players().get(player);
        return data.gold();
    }

    public boolean canAfford(Player player, int amount) {
        return amount <= 0 || balance(player) >= amount;
    }

    /** Adds gold and keeps the lifetime earned tally in step. */
    public void give(Player player, int amount) {
        if (amount <= 0) {
            return;
        }
        PlayerData data = plugin.players().get(player);
        data.gold(data.gold() + amount);
        plugin.players().flush(player, data);
        // Only earnings count towards the tally: moving gold between players
        // in a duel must not let two people farm a "total earned" achievement
        // by passing the same coins back and forth.
        plugin.achievements().bump(player, CounterType.GOLD_EARNED, amount);
    }

    /** Returns false (and says nothing) when the player cannot pay. */
    public boolean take(Player player, int amount) {
        if (amount <= 0) {
            return true;
        }
        PlayerData data = plugin.players().get(player);
        if (data.gold() < amount) {
            return false;
        }
        data.gold(data.gold() - amount);
        plugin.players().flush(player, data);
        return true;
    }

    /** A duel payout: gold that changes hands, not gold that is earned. */
    public void refund(Player player, int amount) {
        if (amount <= 0) {
            return;
        }
        PlayerData data = plugin.players().get(player);
        data.gold(data.gold() + amount);
        plugin.players().flush(player, data);
    }

    public String format(int amount) {
        return ChatColor.GOLD + String.valueOf(amount) + plugin.rpgConfig().goldSymbol();
    }
}
