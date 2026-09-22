package com.rpgcore.plugin.auction;

import com.rpgcore.plugin.RpgCorePlugin;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The auction house, as two chest screens: everything on sale, and your own
 * lots.
 *
 * Each lot is drawn as the item itself with the price written into its
 * tooltip, so "what am I buying" is answered by the thing a player already
 * knows how to read rather than by a description of it. Enchantments and wear
 * show because the real stack is shown - which matters here for the same
 * reason it matters in a duel wager: two items of the same material are not
 * the same item.
 *
 * The lore copied onto the icon is display only. Nothing in this screen can be
 * taken - {@link com.rpgcore.plugin.gui.MenuListener} cancels every click in
 * an RPGCore menu - so the icon is a picture of the escrowed stack, never the
 * stack itself.
 */
public final class AuctionMenu {

    /** Which of the two screens a holder represents. */
    public enum View {
        BROWSE, MINE
    }

    private static final int SIZE = 54;
    private static final int PER_PAGE = 45;

    public static final class Holder implements InventoryHolder {
        private Inventory inventory;
        private final Map<Integer, UUID> lots = new HashMap<>();
        private View view = View.BROWSE;
        private int page;
        private int previousSlot = -1;
        private int nextSlot = -1;
        private int switchSlot = -1;
        private int mailSlot = -1;
        private int backSlot = -1;

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        public UUID lotAt(int slot) {
            return lots.get(slot);
        }

        public View view() {
            return view;
        }

        public int page() {
            return page;
        }

        public int previousSlot() {
            return previousSlot;
        }

        public int nextSlot() {
            return nextSlot;
        }

        public int switchSlot() {
            return switchSlot;
        }

        public int mailSlot() {
            return mailSlot;
        }

        public int backSlot() {
            return backSlot;
        }
    }

    private final RpgCorePlugin plugin;

    public AuctionMenu(RpgCorePlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        open(player, View.BROWSE, 0);
    }

    public void open(Player player, View view, int page) {
        if (!plugin.auctions().enabled()) {
            player.sendMessage(ChatColor.RED + "[경매] 이 서버에서는 경매장을 쓸 수 없습니다.");
            return;
        }

        List<AuctionListing> lots = view == View.MINE
                ? plugin.auctions().listingsOf(player.getUniqueId())
                : plugin.auctions().browse();

        int pages = Math.max(1, (lots.size() + PER_PAGE - 1) / PER_PAGE);
        int shown = Math.clamp(page, 0, pages - 1);

        Holder holder = new Holder();
        holder.view = view;
        holder.page = shown;
        Inventory inv = plugin.getServer().createInventory(holder, SIZE,
                ChatColor.translateAlternateColorCodes('&', plugin.rpgConfig().auctionTitle())
                        + (view == View.MINE ? ChatColor.DARK_GRAY + " - 내 물건" : "")
                        + (pages > 1 ? ChatColor.DARK_GRAY + " (" + (shown + 1) + "/" + pages + ")" : ""));
        holder.setInventory(inv);

        long now = System.currentTimeMillis();
        int first = shown * PER_PAGE;
        for (int i = 0; i < PER_PAGE && first + i < lots.size(); i++) {
            AuctionListing lot = lots.get(first + i);
            inv.setItem(i, icon(player, lot, view, now));
            holder.lots.put(i, lot.id());
        }

        for (int slot = PER_PAGE; slot < SIZE; slot++) {
            inv.setItem(slot, filler());
        }

        if (shown > 0) {
            holder.previousSlot = 45;
            inv.setItem(45, plain(Material.ARROW, ChatColor.YELLOW + "이전 쪽"));
        }
        if (shown < pages - 1) {
            holder.nextSlot = 53;
            inv.setItem(53, plain(Material.ARROW, ChatColor.YELLOW + "다음 쪽"));
        }

        holder.switchSlot = 48;
        inv.setItem(48, view == View.MINE
                ? info(Material.GOLD_BLOCK, ChatColor.GOLD + "전체 경매 보기",
                        List.of(ChatColor.GRAY + "진행 중 " + plugin.auctions().count() + "건"))
                : info(Material.CHEST, ChatColor.AQUA + "내가 올린 물건",
                        List.of(ChatColor.GRAY + "올려 둔 물건 "
                                + plugin.auctions().openCountOf(player.getUniqueId())
                                + " / " + plugin.rpgConfig().auctionMaxListings() + "개")));

        inv.setItem(49, info(Material.GOLD_INGOT, ChatColor.GOLD + "내 골드",
                List.of(ChatColor.YELLOW + String.valueOf(plugin.economy().balance(player))
                                + ChatColor.GRAY + plugin.rpgConfig().goldSymbol(),
                        "",
                        ChatColor.GRAY + "손에 든 물건을 올리려면",
                        ChatColor.YELLOW + "/auction sell <시작가> [즉시구매가]")));

        holder.mailSlot = 50;
        int pending = plugin.mailbox().pending(player.getUniqueId());
        inv.setItem(50, info(pending > 0 ? Material.ENDER_CHEST : Material.BARREL,
                ChatColor.YELLOW + "우편함",
                pending > 0
                        ? List.of(ChatColor.YELLOW + "받을 것이 " + pending + "개 있습니다",
                                "", ChatColor.YELLOW + "클릭하여 받기")
                        : List.of(ChatColor.DARK_GRAY + "비어 있습니다")));

        holder.backSlot = 46;
        inv.setItem(46, plain(Material.OAK_DOOR, ChatColor.GRAY + "메뉴로 돌아가기"));

        player.openInventory(inv);
    }

    /**
     * One lot's tile. The click hints differ per screen and per viewer,
     * because what a click does differs: a seller can only withdraw their own
     * lot, and only while nobody has bid on it.
     */
    private ItemStack icon(Player viewer, AuctionListing lot, View view, long now) {
        ItemStack display = lot.item().clone();
        ItemMeta meta = display.getItemMeta();
        if (meta == null) {
            return display;
        }

        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        if (!lore.isEmpty()) {
            lore.add(ChatColor.DARK_GRAY + "──────────");
        }
        lore.add(ChatColor.GRAY + "판매자: " + ChatColor.WHITE + lot.sellerName());
        lore.add(ChatColor.GRAY + "현재가: " + plugin.economy().format(lot.currentPrice())
                + (lot.hasBids() ? ChatColor.GRAY + " (" + lot.topBidderName() + ")"
                                 : ChatColor.DARK_GRAY + " (입찰 없음)"));
        if (lot.hasBuyNow()) {
            lore.add(ChatColor.GRAY + "즉시구매: " + plugin.economy().format(lot.buyNowPrice()));
        }
        lore.add(ChatColor.GRAY + "남은 시간: " + ChatColor.WHITE + lot.remaining(now));
        // What the market says the same goods are worth, so a bidder can tell
        // a bargain from a fleecing without leaving the screen.
        var reference = plugin.market().byMaterial(lot.item().getType());
        if (reference != null) {
            long marketValue = Math.round(reference.bid(plugin.economyConfig())
                    * lot.item().getAmount());
            lore.add(ChatColor.DARK_GRAY + "시장 시세: "
                    + com.rpgcore.plugin.economy.MarketService.money(reference.mid())
                    + " x" + lot.item().getAmount() + " = 약 " + marketValue);
        }
        lore.add("");

        boolean mine = lot.seller().equals(viewer.getUniqueId());
        if (view == View.MINE || mine) {
            if (lot.hasBids()) {
                lore.add(ChatColor.DARK_GRAY + "입찰이 들어와 내릴 수 없습니다.");
            } else {
                lore.add(ChatColor.YELLOW + "우클릭" + ChatColor.GRAY + ": 내리기");
            }
        } else {
            lore.add(ChatColor.YELLOW + "좌클릭" + ChatColor.GRAY + ": "
                    + plugin.economy().format(plugin.auctions().nextBid(lot)) + ChatColor.GRAY + " 입찰");
            if (lot.hasBuyNow()) {
                lore.add(ChatColor.YELLOW + "우클릭" + ChatColor.GRAY + ": 즉시구매");
            }
        }

        meta.setLore(lore);
        display.setItemMeta(meta);
        return display;
    }

    private ItemStack filler() {
        return plain(Material.BLACK_STAINED_GLASS_PANE, " ");
    }

    private ItemStack plain(Material material, String name) {
        return info(material, name, List.of());
    }

    private ItemStack info(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(name);
        if (!lore.isEmpty()) {
            meta.setLore(lore);
        }
        stack.setItemMeta(meta);
        return stack;
    }
}
