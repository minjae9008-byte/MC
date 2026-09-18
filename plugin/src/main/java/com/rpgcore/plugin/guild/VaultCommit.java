package com.rpgcore.plugin.guild;

import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Works out how to write a finished vault session so that a crash cannot leave
 * an item in two places.
 *
 * The rule everywhere else in this plugin is "write the side giving the item
 * away first". A vault session breaks it, because one session moves items both
 * ways: a deposit is given by the player, a withdrawal is given by the vault,
 * and with one write each there is no order that puts both givers first.
 *
 * Three writes solve it. Between the two files there is a moment where the
 * vault file claims neither what was just put in nor what was just taken out -
 * so whichever way the crash falls, the item is missing rather than doubled.
 *
 * <pre>
 *   writes           vault file            player file       deposited  withdrawn
 *   (before)         has withdrawn         has deposited     player     vault
 *   1. floor         has neither           has deposited     player     nobody
 *   2. player        has neither           has withdrawn     nobody     player
 *   3. vault         has deposited         has withdrawn     vault      player
 * </pre>
 *
 * The middle state is not an empty vault - that would put everything nobody
 * touched into the same limbo, and a crash there would empty the whole thing.
 * It is the per-item-type minimum of what was there when the session opened and
 * what is there now, which is exactly "everything this session did not move".
 *
 * A session that only went one way needs none of this, and pays for none of it:
 * it is two writes in the order that direction wants.
 */
public final class VaultCommit {

    /** The order the vault file and the player file have to be written in. */
    public enum Order {
        /** Deposits only. The player gave, so the player is written first. */
        PLAYER_THEN_STORE,
        /** Withdrawals only. The vault gave, so the vault is written first. */
        STORE_THEN_PLAYER,
        /** Both ways. The vault is written down to the floor first. */
        FLOOR_THEN_PLAYER_THEN_STORE
    }

    private final Order order;
    private final ItemStack[] floor;

    private VaultCommit(Order order, ItemStack[] floor) {
        this.order = order;
        this.floor = floor;
    }

    public Order order() {
        return order;
    }

    /**
     * The contents to publish before the player is written, for
     * {@link Order#FLOOR_THEN_PLAYER_THEN_STORE}. Null for the other orders.
     */
    public ItemStack[] floor() {
        return floor;
    }

    /**
     * @param opened what the vault held when this viewer opened it
     * @param now    what it holds now
     * @param size   the vault's slot count, which the floor has to fit in
     */
    public static VaultCommit plan(ItemStack[] opened, ItemStack[] now, int size) {
        List<Count> before = tally(opened);
        List<Count> after = tally(now);

        boolean tookOut = false;
        for (Count count : before) {
            if (amountOf(after, count.item) < count.amount) {
                tookOut = true;
                break;
            }
        }
        boolean putIn = false;
        for (Count count : after) {
            if (amountOf(before, count.item) < count.amount) {
                putIn = true;
                break;
            }
        }

        if (!tookOut) {
            // Deposits only, or nothing at all.
            return new VaultCommit(Order.PLAYER_THEN_STORE, null);
        }
        if (!putIn) {
            return new VaultCommit(Order.STORE_THEN_PLAYER, null);
        }

        ItemStack[] floor = floor(before, after, size);
        if (floor == null) {
            // Cannot happen - the floor is a subset of what was already in
            // these slots - but if it ever did, publishing a vault that has
            // lost items is far worse than the window this was closing.
            return new VaultCommit(Order.PLAYER_THEN_STORE, null);
        }
        return new VaultCommit(Order.FLOOR_THEN_PLAYER_THEN_STORE, floor);
    }

    /** Everything the session left alone: min(at open, now) per item type. */
    private static ItemStack[] floor(List<Count> before, List<Count> after, int size) {
        ItemStack[] out = new ItemStack[size];
        int slot = 0;
        for (Count count : before) {
            long keep = Math.min(count.amount, amountOf(after, count.item));
            int max = Math.max(1, count.item.getMaxStackSize());
            while (keep > 0) {
                if (slot >= size) {
                    return null;
                }
                ItemStack piece = count.item.clone();
                piece.setAmount((int) Math.min(keep, max));
                out[slot++] = piece;
                keep -= piece.getAmount();
            }
        }
        return out;
    }

    /**
     * Totals by item type. Two stacks count as the same thing when Bukkit says
     * they would stack - so a renamed or damaged sword is its own type, which
     * is what stops a floor from quietly swapping one for another.
     */
    private static List<Count> tally(ItemStack[] items) {
        List<Count> totals = new ArrayList<>();
        if (items == null) {
            return totals;
        }
        for (ItemStack stack : items) {
            if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) {
                continue;
            }
            Count found = null;
            for (Count count : totals) {
                if (count.item.isSimilar(stack)) {
                    found = count;
                    break;
                }
            }
            if (found == null) {
                ItemStack one = stack.clone();
                one.setAmount(1);
                totals.add(new Count(one, stack.getAmount()));
            } else {
                found.amount += stack.getAmount();
            }
        }
        return totals;
    }

    private static long amountOf(List<Count> totals, ItemStack item) {
        for (Count count : totals) {
            if (count.item.isSimilar(item)) {
                return count.amount;
            }
        }
        return 0L;
    }

    /** One item type and how much of it there is. */
    private static final class Count {
        private final ItemStack item;
        private long amount;

        private Count(ItemStack item, long amount) {
            this.item = item;
            this.amount = amount;
        }
    }
}
