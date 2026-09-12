package com.rpgcore.plugin.trade;

import com.rpgcore.plugin.RpgCorePlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One trade between two players, backed by a single shared chest inventory
 * that both of them have open.
 *
 * Sharing one inventory rather than mirroring two is what keeps the two sides
 * honest: there is only ever one set of items, so nothing can be shown to one
 * player and not the other. Who may touch which half is enforced per click.
 *
 * The invariants that matter, because this is where trade systems dupe items:
 *   - every path out of a trade goes through complete() or cancel(), both of
 *     which are guarded by {@code finished} so they can run exactly once;
 *   - cancel() hands every stack back to whoever put it in;
 *   - complete() checks both players have room *before* it moves anything;
 *   - any change to either offer clears both confirmations, and it clears them
 *     synchronously, inside the click that makes the change.
 *
 * That last word is load-bearing. Both "the trade is settled" and "the offer
 * changed" have to run after vanilla has applied the click, so both are
 * scheduled; but a client can send [confirm] and [take my stack back] in one
 * packet batch, and scheduled tasks then run in the order they were queued -
 * complete() first, looking at an offer half that vanilla has *already*
 * emptied onto the cursor. Clearing the flags while still inside the click
 * event is what makes complete()'s re-check see the withdrawal, so the only
 * way to reach a settlement is for both offers to have stood still.
 */
public final class TradeSession implements InventoryHolder {

    static final int SIZE = 54;
    /** Four columns each side, four rows deep. */
    private static final int[] LEFT_SLOTS = {0, 1, 2, 3, 9, 10, 11, 12, 18, 19, 20, 21, 27, 28, 29, 30};
    private static final int[] RIGHT_SLOTS = {5, 6, 7, 8, 14, 15, 16, 17, 23, 24, 25, 26, 32, 33, 34, 35};
    private static final int[] DIVIDER = {4, 13, 22, 31, 40, 49};
    private static final int LEFT_LABEL = 36;
    private static final int RIGHT_LABEL = 44;
    private static final int LEFT_CONFIRM = 47;
    private static final int RIGHT_CONFIRM = 51;

    private final RpgCorePlugin plugin;
    private final Player left;
    private final Player right;
    private final Inventory inventory;

    private boolean leftReady;
    private boolean rightReady;
    private boolean finished;

    TradeSession(RpgCorePlugin plugin, Player left, Player right) {
        this.plugin = plugin;
        this.left = left;
        this.right = right;
        String title = ChatColor.translateAlternateColorCodes('&',
                plugin.rpgConfig().tradeTitle());
        this.inventory = Bukkit.createInventory(this, SIZE, title);
        decorate();
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public Player left() {
        return left;
    }

    public Player right() {
        return right;
    }

    void open() {
        left.openInventory(inventory);
        right.openInventory(inventory);
        left.sendMessage(ChatColor.GREEN + "[거래] " + right.getName() + " 와(과) 거래를 시작합니다.");
        right.sendMessage(ChatColor.GREEN + "[거래] " + left.getName() + " 와(과) 거래를 시작합니다.");
    }

    // ---------------------------------------------------------------- clicks

    void handleClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || finished) {
            event.setCancelled(true);
            return;
        }
        int raw = event.getRawSlot();
        boolean top = raw >= 0 && raw < SIZE;

        // Double-click gathers matching items from anywhere in the view,
        // including the other player's offer, so it is never allowed here.
        if (event.getClick() == ClickType.DOUBLE_CLICK) {
            event.setCancelled(true);
            return;
        }

        if (!top) {
            if (event.isShiftClick()) {
                event.setCancelled(true);
                shiftIntoOwnOffer(event, player);
            }
            return;
        }

        if (raw == confirmSlot(player)) {
            event.setCancelled(true);
            toggleReady(player);
            return;
        }
        if (!isOwnOfferSlot(player, raw)) {
            event.setCancelled(true);
            return;
        }
        // A legal move into or out of the player's own offer. Vanilla applies
        // it after this handler returns, so the redraw has to wait a tick -
        // but the confirmations are dropped right now, before the item moves,
        // so nothing queued behind this click can settle on the old offer.
        if (!isNoOpClick(event)) {
            clearReady();
            plugin.getServer().getScheduler().runTask(plugin, this::onOfferChanged);
        }
    }

    /**
     * A click that cannot change the offer: empty cursor onto an empty slot.
     * Without this, idly clicking a blank offer slot would cancel a
     * confirmation the other player is waiting on.
     */
    private boolean isNoOpClick(InventoryClickEvent event) {
        ItemStack cursor = event.getCursor();
        ItemStack current = event.getCurrentItem();
        return (cursor == null || cursor.getType().isAir())
                && (current == null || current.getType().isAir())
                && event.getClick() != ClickType.NUMBER_KEY
                && event.getClick() != ClickType.SWAP_OFFHAND;
    }

    private void clearReady() {
        leftReady = false;
        rightReady = false;
    }

    private void shiftIntoOwnOffer(InventoryClickEvent event, Player player) {
        ItemStack moving = event.getCurrentItem();
        if (moving == null || moving.getType().isAir() || event.getClickedInventory() == null) {
            return;
        }
        ItemStack leftover = addToOffer(ownSlots(player), moving.clone());
        event.getClickedInventory().setItem(event.getSlot(), leftover);
        // This one moves the items itself rather than letting vanilla do it,
        // so the whole change - flags included - lands inside the click.
        onOfferChanged();
    }

    /** Merges into matching stacks first, then fills empty offer slots. */
    private ItemStack addToOffer(int[] slots, ItemStack stack) {
        for (int slot : slots) {
            if (stack.getAmount() <= 0) {
                return null;
            }
            ItemStack existing = inventory.getItem(slot);
            if (existing != null && existing.isSimilar(stack)) {
                int room = existing.getMaxStackSize() - existing.getAmount();
                if (room > 0) {
                    int moved = Math.min(room, stack.getAmount());
                    existing.setAmount(existing.getAmount() + moved);
                    inventory.setItem(slot, existing);
                    stack.setAmount(stack.getAmount() - moved);
                }
            }
        }
        for (int slot : slots) {
            if (stack.getAmount() <= 0) {
                return null;
            }
            if (inventory.getItem(slot) == null) {
                inventory.setItem(slot, stack.clone());
                stack.setAmount(0);
            }
        }
        return stack.getAmount() > 0 ? stack : null;
    }

    /**
     * The redraw half of an offer change; the flags themselves are already
     * down (see {@link #handleClick}). Runs a tick late, once vanilla has
     * actually moved the item, so the window both players see matches it.
     */
    private void onOfferChanged() {
        if (finished) {
            return;
        }
        clearReady();
        redrawButtons();
        refreshViewers();
    }

    private void toggleReady(Player player) {
        if (left.equals(player)) {
            leftReady = !leftReady;
        } else {
            rightReady = !rightReady;
        }
        redrawButtons();
        refreshViewers();
        if (leftReady && rightReady) {
            // Out of the click handler before moving anything around.
            plugin.getServer().getScheduler().runTask(plugin, this::complete);
        }
    }

    // ------------------------------------------------------------- finishing

    void complete() {
        if (finished) {
            return;
        }
        // Re-read the confirmations rather than trusting the ones that queued
        // this call: a click processed after the confirm, in the same batch,
        // may have taken an offered stack back out since.
        if (!leftReady || !rightReady) {
            redrawButtons();
            refreshViewers();
            return;
        }
        if (!left.isOnline() || !right.isOnline()) {
            cancel("상대가 접속을 종료했습니다.");
            return;
        }

        List<ItemStack> fromLeft = itemsIn(LEFT_SLOTS);
        List<ItemStack> fromRight = itemsIn(RIGHT_SLOTS);
        if (!hasRoom(right, fromLeft) || !hasRoom(left, fromRight)) {
            clearReady();
            redrawButtons();
            refreshViewers();
            left.sendMessage(ChatColor.RED + "[거래] 인벤토리 공간이 부족해 거래를 끝낼 수 없습니다.");
            right.sendMessage(ChatColor.RED + "[거래] 인벤토리 공간이 부족해 거래를 끝낼 수 없습니다.");
            return;
        }

        finished = true;
        clear(LEFT_SLOTS);
        clear(RIGHT_SLOTS);
        give(right, fromLeft);
        give(left, fromRight);
        closeBoth();
        left.sendMessage(ChatColor.GREEN + "[거래] 거래가 완료되었습니다.");
        right.sendMessage(ChatColor.GREEN + "[거래] 거래가 완료되었습니다.");
        plugin.trades().end(this);
    }

    /** Ends the trade and hands every offered stack back to its owner. */
    void cancel(String reason) {
        if (finished) {
            return;
        }
        finished = true;
        give(left, itemsIn(LEFT_SLOTS));
        give(right, itemsIn(RIGHT_SLOTS));
        clear(LEFT_SLOTS);
        clear(RIGHT_SLOTS);
        closeBoth();
        String message = ChatColor.YELLOW + "[거래] 거래가 취소되었습니다." + (reason == null ? "" : " (" + reason + ")");
        if (left.isOnline()) {
            left.sendMessage(message);
        }
        if (right.isOnline()) {
            right.sendMessage(message);
        }
        plugin.trades().end(this);
    }

    /** A viewer closed the window: that is a cancel, unless we closed it. */
    void handleClose(Player player) {
        if (!finished) {
            cancel(player.getName() + " 이(가) 창을 닫았습니다.");
        }
    }

    private void closeBoth() {
        // Closing from inside an inventory event desyncs the client, so both
        // closes wait for the next tick.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (left.isOnline() && left.getOpenInventory().getTopInventory().equals(inventory)) {
                left.closeInventory();
            }
            if (right.isOnline() && right.getOpenInventory().getTopInventory().equals(inventory)) {
                right.closeInventory();
            }
        });
    }

    private List<ItemStack> itemsIn(int[] slots) {
        List<ItemStack> items = new ArrayList<>();
        for (int slot : slots) {
            ItemStack item = inventory.getItem(slot);
            if (item != null && !item.getType().isAir()) {
                items.add(item.clone());
            }
        }
        return items;
    }

    private void clear(int[] slots) {
        for (int slot : slots) {
            inventory.setItem(slot, null);
        }
    }

    /** Tries the whole delivery against a copy first, so it is all or nothing. */
    private boolean hasRoom(Player player, List<ItemStack> items) {
        if (items.isEmpty()) {
            return true;
        }
        Inventory probe = Bukkit.createInventory(null, 36);
        probe.setContents(Arrays.copyOf(player.getInventory().getStorageContents(), 36));
        return probe.addItem(items.toArray(new ItemStack[0])).isEmpty();
    }

    private void give(Player player, List<ItemStack> items) {
        for (ItemStack item : items) {
            for (ItemStack leftover : player.getInventory().addItem(item).values()) {
                // Only reachable when a player's inventory filled up between
                // the room check and here; the floor beats deleting it.
                player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            }
        }
    }

    // ---------------------------------------------------------------- layout

    private boolean isOwnOfferSlot(Player player, int slot) {
        for (int own : ownSlots(player)) {
            if (own == slot) {
                return true;
            }
        }
        return false;
    }

    private int[] ownSlots(Player player) {
        return left.equals(player) ? LEFT_SLOTS : RIGHT_SLOTS;
    }

    private int confirmSlot(Player player) {
        return left.equals(player) ? LEFT_CONFIRM : RIGHT_CONFIRM;
    }

    private void decorate() {
        ItemStack filler = named(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int slot = 36; slot < SIZE; slot++) {
            inventory.setItem(slot, filler);
        }
        ItemStack divider = named(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int slot : DIVIDER) {
            inventory.setItem(slot, divider);
        }
        inventory.setItem(LEFT_LABEL, named(Material.PAPER,
                ChatColor.AQUA + left.getName() + ChatColor.GRAY + " 이(가) 내놓는 것"));
        inventory.setItem(RIGHT_LABEL, named(Material.PAPER,
                ChatColor.AQUA + right.getName() + ChatColor.GRAY + " 이(가) 내놓는 것"));
        redrawButtons();
    }

    private void redrawButtons() {
        inventory.setItem(LEFT_CONFIRM, confirmButton(left.getName(), leftReady));
        inventory.setItem(RIGHT_CONFIRM, confirmButton(right.getName(), rightReady));
    }

    private ItemStack confirmButton(String owner, boolean ready) {
        ItemStack item = new ItemStack(ready ? Material.LIME_CONCRETE : Material.RED_CONCRETE);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ready
                ? ChatColor.GREEN + owner + ": 확정함"
                : ChatColor.RED + owner + ": 확정 안 함");
        meta.setLore(List.of(
                ChatColor.GRAY + "자기 쪽 버튼을 눌러 확정합니다.",
                ChatColor.GRAY + "물건이 바뀌면 양쪽 확정이 풀립니다."));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack named(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Both players look at the same inventory, but a click by one of them is
     * only echoed to that client, so the other is told to redraw.
     */
    private void refreshViewers() {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (left.isOnline()) {
                left.updateInventory();
            }
            if (right.isOnline()) {
                right.updateInventory();
            }
        });
    }
}
