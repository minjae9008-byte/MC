package com.rpgcore.plugin.gui;

import com.rpgcore.plugin.RpgCorePlugin;
import com.rpgcore.plugin.auction.AuctionMenu;
import com.rpgcore.plugin.collection.CollectionMenu;
import com.rpgcore.plugin.menu.MainMenu;
import com.rpgcore.plugin.job.JobMenu;
import com.rpgcore.plugin.progress.TitleMenu;
import com.rpgcore.plugin.job.RpgJob;
import com.rpgcore.plugin.stats.StatType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryHolder;

/**
 * Clicks in RPGCore's menus.
 *
 * Every menu here is read-only furniture, so the first thing any click does is
 * get cancelled; what follows is only ever "which button was that". Opening or
 * closing an inventory from inside InventoryClickEvent desyncs the client, so
 * both are scheduled for the next tick.
 */
public final class MenuListener implements Listener {

    private final RpgCorePlugin plugin;

    public MenuListener(RpgCorePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (!plugin.isOwnMenu(holder) || holder instanceof com.rpgcore.plugin.trade.TradeSession) {
            // The trade window is the one RPGCore screen players may put items
            // into, so it has a listener of its own.
            return;
        }
        // Cancel every interaction with the menu, including shift-clicks from
        // the player's own inventory, so menu items can never be taken out.
        event.setCancelled(true);

        if (event.getClickedInventory() == null || event.getClickedInventory().getHolder() != holder) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        if (holder instanceof JobMenu.Holder jobs) {
            RpgJob job = jobs.jobAt(event.getRawSlot());
            if (job != null && plugin.jobs().choose(player, job)) {
                later(player, player::closeInventory);
            }
            return;
        }

        if (holder instanceof TitleMenu.Holder menu) {
            String id = menu.titleAt(event.getRawSlot());
            if (id == null) {
                return;
            }
            if (id.isEmpty()) {
                plugin.titles().wear(player, null);
                player.sendMessage(org.bukkit.ChatColor.YELLOW + "[칭호] 칭호를 뗐습니다.");
            } else if (!plugin.titles().hasEarned(player, id)) {
                return;
            } else {
                plugin.titles().wear(player, id);
                player.sendMessage(org.bukkit.ChatColor.GREEN + "[칭호] "
                        + plugin.titles().byId(id).display()
                        + org.bukkit.ChatColor.GREEN + " 을(를) 착용했습니다.");
            }
            later(player, () -> plugin.titleMenu().open(player));
            return;
        }

        if (holder instanceof MainMenu.Holder menu) {
            MainMenu.Action action = menu.actionAt(event.getRawSlot());
            if (action != null) {
                run(player, action);
            }
            return;
        }

        if (holder instanceof AuctionMenu.Holder menu) {
            auctionClick(player, menu, event);
            return;
        }

        if (holder instanceof CollectionMenu.Holder menu) {
            int slot = event.getRawSlot();
            if (slot == menu.backSlot()) {
                later(player, () -> plugin.collectionMenu().open(player));
            } else if (slot == menu.previousSlot()) {
                later(player, () -> plugin.collectionMenu()
                        .openCategory(player, menu.category(), menu.page() - 1));
            } else if (slot == menu.nextSlot()) {
                later(player, () -> plugin.collectionMenu()
                        .openCategory(player, menu.category(), menu.page() + 1));
            } else {
                String category = menu.linkAt(slot);
                if (category != null) {
                    later(player, () -> plugin.collectionMenu().openCategory(player, category, 0));
                }
            }
            return;
        }

        if (!(holder instanceof StatsMenu.Holder)) {
            return;
        }

        StatsMenu.Holder stats = (StatsMenu.Holder) holder;
        int slot = event.getRawSlot();
        if (slot == stats.closeSlot()) {
            later(player, player::closeInventory);
            return;
        }
        if (slot == StatsMenu.JOB_SLOT) {
            later(player, () -> plugin.jobMenu().open(player));
            return;
        }

        StatType type = StatsMenu.statAt(slot);
        if (type == null) {
            return;
        }
        // The allocation applies straight away; only the redraw waits a tick.
        if (plugin.stats().allocate(player, type)) {
            later(player, () -> plugin.openStatsMenu(player));
        }
    }

    /**
     * The hub's buttons. Screens open next tick like every other menu here;
     * the ones that answer in chat close first, so the player is not left
     * reading text through a window.
     */
    private void run(Player player, MainMenu.Action action) {
        switch (action) {
            case STATS -> later(player, () -> plugin.openStatsMenu(player));
            case JOBS -> later(player, () -> plugin.jobMenu().open(player));
            case TITLES -> later(player, () -> plugin.titleMenu().open(player));
            case COLLECTION -> later(player, () -> plugin.collectionMenu().open(player));
            case AUCTION -> later(player, () -> plugin.auctionMenu().open(player));
            case GUILD -> chat(player, "guild info");
            case GUILD_VAULT -> later(player, () -> plugin.guilds().openVault(player));
            case MAILBOX -> {
                if (plugin.mailbox().pending(player.getUniqueId()) == 0) {
                    player.sendMessage(org.bukkit.ChatColor.GRAY + "[우편] 받을 것이 없습니다.");
                    return;
                }
                // Collecting puts items in the inventory the player is looking
                // at, so the window has to go first or they will not see them.
                later(player, () -> {
                    player.closeInventory();
                    plugin.mailbox().collect(player);
                });
            }
            case ACHIEVEMENTS -> chat(player, "achievements");
            case LEADERBOARD -> chat(player, "leaderboard");
            case PARTY -> chat(player, "party");
            case TRADE_HELP -> chat(player, "trade");
            case DUEL_HELP -> chat(player, "duel");
            case CLOSE -> later(player, player::closeInventory);
        }
    }

    /** Closes the window, then runs a command whose answer is chat text. */
    private void chat(Player player, String command) {
        later(player, () -> {
            player.closeInventory();
            player.performCommand(command);
        });
    }

    /**
     * A click on a lot. Left bids, right buys or withdraws - and which of
     * those a right click means depends on whose lot it is, so it is decided
     * here rather than trusted from the screen that drew it.
     */
    private void auctionClick(Player player, AuctionMenu.Holder menu, InventoryClickEvent event) {
        int slot = event.getRawSlot();
        if (slot == menu.backSlot()) {
            later(player, () -> plugin.mainMenu().open(player));
            return;
        }
        if (slot == menu.previousSlot()) {
            later(player, () -> plugin.auctionMenu().open(player, menu.view(), menu.page() - 1));
            return;
        }
        if (slot == menu.nextSlot()) {
            later(player, () -> plugin.auctionMenu().open(player, menu.view(), menu.page() + 1));
            return;
        }
        if (slot == menu.switchSlot()) {
            AuctionMenu.View next = menu.view() == AuctionMenu.View.MINE
                    ? AuctionMenu.View.BROWSE : AuctionMenu.View.MINE;
            later(player, () -> plugin.auctionMenu().open(player, next, 0));
            return;
        }
        if (slot == menu.mailSlot()) {
            if (plugin.mailbox().pending(player.getUniqueId()) == 0) {
                return;
            }
            later(player, () -> {
                player.closeInventory();
                plugin.mailbox().collect(player);
            });
            return;
        }

        java.util.UUID lotId = menu.lotAt(slot);
        if (lotId == null) {
            return;
        }
        com.rpgcore.plugin.auction.AuctionListing lot = plugin.auctions().byId(lotId);
        if (lot == null) {
            player.sendMessage(org.bukkit.ChatColor.RED + "[경매] 이미 끝난 경매입니다.");
            later(player, () -> plugin.auctionMenu().open(player, menu.view(), menu.page()));
            return;
        }

        boolean mine = lot.seller().equals(player.getUniqueId());
        // Acted on straight away - the click is the confirmation, and a lot
        // can be taken by somebody else in the tick a redraw would cost.
        if (mine) {
            if (event.isRightClick()) {
                plugin.auctions().cancel(player, lotId);
            } else {
                player.sendMessage(org.bukkit.ChatColor.GRAY + "[경매] 자기 물건입니다. 우클릭으로 내릴 수 있습니다.");
                return;
            }
        } else if (event.isRightClick()) {
            plugin.auctions().buyNow(player, lotId);
        } else {
            plugin.auctions().bid(player, lotId);
        }
        later(player, () -> plugin.auctionMenu().open(player, menu.view(), menu.page()));
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (plugin.isOwnMenu(holder) && !(holder instanceof com.rpgcore.plugin.trade.TradeSession)) {
            event.setCancelled(true);
        }
    }

    private void later(Player player, Runnable action) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                action.run();
            }
        });
    }
}
