package com.rpgcore.plugin.util;

import org.bukkit.entity.Player;

/**
 * Commits an item moving between a player's inventory and one of this
 * plugin's files.
 *
 * The two halves of such a move are written by different systems on different
 * schedules. A player's inventory belongs to vanilla, which writes it on quit
 * and on its own autosave timer - minutes apart. An auction lot, a duel stake
 * or a guild vault belongs to this plugin, which writes within a second. So
 * between the two there is a window, minutes wide, where the server has
 * recorded one half of a move and not the other.
 *
 * That window was measured rather than guessed: a hard kill after listing a
 * sword left the sword in the player's saved inventory AND the lot in
 * auctions.yml. The item existed twice.
 *
 * Two rules close it as far as it can be closed without a shared transaction
 * log:
 *
 *   - force both halves out together, which turns minutes into milliseconds;
 *   - within that, write the side GIVING the item away first. Then a crash in
 *     the remaining gap loses the item rather than copying it. That is the
 *     right way round to fail: a lost item is one support request, while a
 *     duplicated one is an economy quietly filling up with free diamonds, and
 *     nobody reports that.
 *
 * Not for hot paths - saving a player rewrites their whole data file. These
 * are deliberate, occasional acts: listing a lot, staking a duel, closing a
 * vault, collecting the mail.
 */
public final class HandOver {

    private HandOver() {
    }

    /**
     * The player just handed something over - a lot listed, a stake put up, a
     * stack dropped into a guild vault.
     *
     * The player is written first, so the inventory on disk stops claiming the
     * item before the file that now owns it says so.
     */
    public static void takenFromPlayer(Player player, DeferredSave store) {
        if (player != null && player.isOnline()) {
            player.saveData();
        }
        if (store != null) {
            store.flushBlocking();
        }
    }

    /**
     * The player just received something - mail collected, a lot won, a stack
     * taken back out of a vault.
     *
     * The store is written first, for the same reason in the other direction.
     */
    public static void givenToPlayer(Player player, DeferredSave store) {
        if (store != null) {
            store.flushBlocking();
        }
        if (player != null && player.isOnline()) {
            player.saveData();
        }
    }
}
