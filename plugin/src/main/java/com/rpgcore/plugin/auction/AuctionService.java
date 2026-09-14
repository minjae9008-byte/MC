package com.rpgcore.plugin.auction;

import com.rpgcore.plugin.RpgCorePlugin;
import com.rpgcore.plugin.collection.CollectionService;
import com.rpgcore.plugin.util.DeferredSave;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The auction house: what gold is actually for.
 *
 * A lot is an English auction with an optional buy-now price. Bidding takes
 * the bidder's gold there and then and refunds whoever it outbids, so the
 * balance every player sees is always what they can still spend - there is no
 * such thing as a bid someone cannot honour, and no moment where two players
 * have both "committed" the same coins.
 *
 * Everything that can end a lot - a purchase, the clock running out, the
 * seller cancelling - claims it through {@link AuctionListing#settle()} first,
 * which succeeds exactly once. Combined with every path here running on the
 * main thread, that is what makes two players clicking Buy in the same tick
 * resolve to one sale and one "someone got there first", rather than to two
 * items out of one.
 *
 * Nothing is ever handed to a player who is not there to take it: sale
 * proceeds, won lots, unsold lots and outbid refunds all go through the
 * {@link com.rpgcore.plugin.mail.MailboxService}, which delivers now if it can
 * and holds it on disk if it cannot.
 */
public final class AuctionService {

    private final RpgCorePlugin plugin;
    private final AuctionStorage storage;
    /** Live lots, newest last, keyed by id so a click can find one in O(1). */
    private final Map<UUID, AuctionListing> listings = new LinkedHashMap<>();
    /**
     * When the soonest lot ends. The tick pump runs twenty times a second and
     * almost never has anything to do, so it compares against this one number
     * instead of walking the book. Kept current by {@link #save()}, which
     * every mutation already goes through.
     */
    private long soonestEndMs = Long.MAX_VALUE;

    private final DeferredSave writer;

    public AuctionService(RpgCorePlugin plugin) {
        this.plugin = plugin;
        this.storage = new AuctionStorage(plugin);
        this.writer = new DeferredSave(plugin, plugin.saveQueue(), "auctions.yml",
                () -> storage.build(listings.values()));
    }

    public boolean enabled() {
        return plugin.rpgConfig().auctionEnabled();
    }

    public void load() {
        listings.clear();
        for (AuctionListing listing : storage.load()) {
            listings.put(listing.id(), listing);
        }
        refreshSoonestEnd();
        plugin.getLogger().info("Auction lots loaded: " + listings.size() + ".");
    }

    /**
     * Marks the book stale; the write is coalesced off the main thread. A busy
     * auction bids far faster than it needs writing, and every bid used to
     * rebuild the whole file on the spot.
     */
    public void save() {
        refreshSoonestEnd();
        writer.markDirty();
    }

    /** Builds and writes on this thread. For shutdown only. */
    public void saveNow() {
        writer.flushNow();
    }

    public void flushIfDirty() {
        writer.flushIfDirty();
    }

    private void refreshSoonestEnd() {
        long soonest = Long.MAX_VALUE;
        for (AuctionListing listing : listings.values()) {
            if (!listing.settled()) {
                soonest = Math.min(soonest, listing.endsAtMs());
            }
        }
        soonestEndMs = soonest;
    }

    public int count() {
        return listings.size();
    }

    public AuctionListing byId(UUID id) {
        return id == null ? null : listings.get(id);
    }

    /** Live lots, soonest to end first - the order a buyer wants to browse. */
    public List<AuctionListing> browse() {
        List<AuctionListing> open = new ArrayList<>();
        for (AuctionListing listing : listings.values()) {
            if (!listing.settled()) {
                open.add(listing);
            }
        }
        open.sort(Comparator.comparingLong(AuctionListing::endsAtMs));
        return open;
    }

    /** One player's own lots, newest first. */
    public List<AuctionListing> listingsOf(UUID seller) {
        List<AuctionListing> mine = new ArrayList<>();
        for (AuctionListing listing : listings.values()) {
            if (!listing.settled() && listing.seller().equals(seller)) {
                mine.add(listing);
            }
        }
        mine.sort(Comparator.comparingLong(AuctionListing::createdAtMs).reversed());
        return mine;
    }

    public int openCountOf(UUID seller) {
        int open = 0;
        for (AuctionListing listing : listings.values()) {
            if (!listing.settled() && listing.seller().equals(seller)) {
                open++;
            }
        }
        return open;
    }

    // --------------------------------------------------------------- selling

    /**
     * Puts the stack in the player's main hand up for auction.
     *
     * The item is taken out of the hand before the lot exists, and the listing
     * fee is taken before that - so a failure at any point leaves the player
     * with exactly what they started with, and there is never a window where
     * the item is both in a lot and in an inventory.
     */
    public void sell(Player seller, int startPrice, int buyNowPrice) {
        if (!enabled()) {
            seller.sendMessage(ChatColor.RED + "[경매] 이 서버에서는 경매장을 쓸 수 없습니다.");
            return;
        }
        // A trade window borrows items in ways that are hard to reason about
        // alongside an escrow; refusing is cheaper than getting it subtly wrong.
        if (plugin.trades().sessionOf(seller) != null) {
            seller.sendMessage(ChatColor.RED + "[경매] 거래 중에는 물건을 올릴 수 없습니다.");
            return;
        }

        ItemStack held = seller.getInventory().getItemInMainHand();
        if (held == null || held.getType().isAir()) {
            seller.sendMessage(ChatColor.RED + "[경매] 손에 든 아이템이 없습니다.");
            return;
        }

        int max = plugin.rpgConfig().auctionMaxListings();
        if (openCountOf(seller.getUniqueId()) >= max) {
            seller.sendMessage(ChatColor.RED + "[경매] 동시에 올릴 수 있는 물건은 " + max + "개까지입니다.");
            return;
        }
        if (!validPrices(seller, startPrice, buyNowPrice)) {
            return;
        }

        int fee = listingFee(startPrice, buyNowPrice);
        if (fee > 0 && !plugin.economy().take(seller, fee)) {
            seller.sendMessage(ChatColor.RED + "[경매] 등록 수수료 " + fee + " 골드가 부족합니다. (보유 "
                    + plugin.economy().balance(seller) + ")");
            return;
        }

        ItemStack lot = held.clone();
        seller.getInventory().setItemInMainHand(null);

        long now = System.currentTimeMillis();
        long ends = now + plugin.rpgConfig().auctionDurationMinutes() * 60_000L;
        AuctionListing listing = new AuctionListing(UUID.randomUUID(), seller.getUniqueId(),
                seller.getName(), lot, startPrice, buyNowPrice, now, ends);
        listings.put(listing.id(), listing);
        save();

        seller.sendMessage(ChatColor.GREEN + "[경매] " + ChatColor.WHITE
                + CollectionService.nameOf(lot) + ChatColor.GRAY + " x" + lot.getAmount()
                + ChatColor.GREEN + " 을(를) 경매에 올렸습니다.");
        seller.sendMessage(ChatColor.GRAY + "  시작가 " + plugin.economy().format(startPrice)
                + (buyNowPrice > 0 ? ChatColor.GRAY + " / 즉시구매 " + plugin.economy().format(buyNowPrice) : "")
                + (fee > 0 ? ChatColor.GRAY + " / 수수료 " + plugin.economy().format(fee) : ""));
        seller.playSound(seller.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0F, 1.2F);
        announce(ChatColor.GOLD + "[경매] " + ChatColor.WHITE + seller.getName() + ChatColor.GOLD
                + " 님이 " + ChatColor.WHITE + CollectionService.nameOf(lot)
                + ChatColor.GRAY + " x" + lot.getAmount() + ChatColor.GOLD + " 을(를) 올렸습니다.", seller);
    }

    private boolean validPrices(Player seller, int startPrice, int buyNowPrice) {
        int min = plugin.rpgConfig().auctionMinPrice();
        int max = plugin.rpgConfig().auctionMaxPrice();
        if (startPrice < min) {
            seller.sendMessage(ChatColor.RED + "[경매] 시작가는 " + min + " 골드 이상이어야 합니다.");
            return false;
        }
        if (startPrice > max) {
            seller.sendMessage(ChatColor.RED + "[경매] 시작가는 " + max + " 골드까지입니다.");
            return false;
        }
        if (buyNowPrice > 0 && buyNowPrice > max) {
            seller.sendMessage(ChatColor.RED + "[경매] 즉시구매가는 " + max + " 골드까지입니다.");
            return false;
        }
        if (buyNowPrice > 0 && buyNowPrice < startPrice) {
            seller.sendMessage(ChatColor.RED + "[경매] 즉시구매가는 시작가보다 낮을 수 없습니다.");
            return false;
        }
        return true;
    }

    private int listingFee(int startPrice, int buyNowPrice) {
        int percent = plugin.rpgConfig().auctionListingFeePercent();
        if (percent <= 0) {
            return 0;
        }
        // Based on whichever price the seller is really asking, so a token
        // start price under a high buy-now does not dodge the fee.
        long basis = Math.max(startPrice, buyNowPrice);
        return (int) Math.min(basis * percent / 100L, Integer.MAX_VALUE);
    }

    // --------------------------------------------------------------- bidding

    /** The least a new bid may be; also what the Bid button charges. */
    public int nextBid(AuctionListing listing) {
        if (!listing.hasBids()) {
            return listing.startPrice();
        }
        int percent = Math.max(1, plugin.rpgConfig().auctionBidIncrementPercent());
        long step = Math.max(1L, (long) listing.topBid() * percent / 100L);
        return (int) Math.min((long) listing.topBid() + step, Integer.MAX_VALUE);
    }

    /**
     * Bids the minimum increment on a lot.
     *
     * The order matters: the lot is re-read and re-checked, the new bid is
     * taken from the bidder, and only then is the previous bidder refunded.
     * Refunding first would leave a window in which the same lot was backed by
     * nobody's gold.
     */
    public void bid(Player bidder, UUID listingId) {
        AuctionListing listing = listings.get(listingId);
        if (listing == null || listing.settled()) {
            bidder.sendMessage(ChatColor.RED + "[경매] 이미 끝난 경매입니다.");
            return;
        }
        if (listing.seller().equals(bidder.getUniqueId())) {
            bidder.sendMessage(ChatColor.RED + "[경매] 자기 물건에는 입찰할 수 없습니다.");
            return;
        }
        if (bidder.getUniqueId().equals(listing.topBidder())) {
            bidder.sendMessage(ChatColor.YELLOW + "[경매] 이미 최고 입찰자입니다. ("
                    + plugin.economy().format(listing.topBid()) + ChatColor.YELLOW + ")");
            return;
        }
        if (listing.expired(System.currentTimeMillis())) {
            bidder.sendMessage(ChatColor.RED + "[경매] 이미 종료 시각이 지난 경매입니다.");
            return;
        }

        int amount = nextBid(listing);
        if (listing.hasBuyNow() && amount >= listing.buyNowPrice()) {
            bidder.sendMessage(ChatColor.YELLOW + "[경매] 다음 입찰가가 즉시구매가 이상입니다. "
                    + "즉시구매(" + plugin.economy().format(listing.buyNowPrice()) + ChatColor.YELLOW + ")를 쓰세요.");
            return;
        }
        if (!plugin.economy().take(bidder, amount)) {
            bidder.sendMessage(ChatColor.RED + "[경매] " + amount + " 골드가 필요합니다. (보유 "
                    + plugin.economy().balance(bidder) + ")");
            return;
        }

        UUID outbid = listing.topBidder();
        int outbidAmount = listing.topBid();
        listing.bid(bidder.getUniqueId(), bidder.getName(), amount);
        extendIfSniped(listing);
        save();

        if (outbid != null) {
            plugin.mailbox().giveGold(outbid, outbidAmount, "입찰 반환");
            Player previous = plugin.getServer().getPlayer(outbid);
            if (previous != null && previous.isOnline()) {
                previous.sendMessage(ChatColor.RED + "[경매] " + ChatColor.WHITE
                        + CollectionService.nameOf(listing.item()) + ChatColor.RED
                        + " 입찰에서 밀렸습니다. " + ChatColor.GRAY + "(현재 "
                        + plugin.economy().format(amount) + ChatColor.GRAY + ")");
                previous.playSound(previous.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0F, 1.0F);
            }
        }

        bidder.sendMessage(ChatColor.GREEN + "[경매] " + plugin.economy().format(amount)
                + ChatColor.GREEN + " 에 입찰했습니다. " + ChatColor.GRAY + "("
                + listing.remaining(System.currentTimeMillis()) + " 남음)");
        bidder.playSound(bidder.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1.0F, 1.4F);
        tellSeller(listing, ChatColor.GOLD + "[경매] " + ChatColor.WHITE
                + CollectionService.nameOf(listing.item()) + ChatColor.GOLD + " 에 "
                + plugin.economy().format(amount) + ChatColor.GOLD + " 입찰이 들어왔습니다.");
    }

    /**
     * Pushes back the end time when a bid lands in the closing seconds.
     *
     * Without it the winning move is to bid in the last tick, which is a
     * contest of latency rather than of price, and leaves every other bidder
     * with no chance to answer.
     */
    private void extendIfSniped(AuctionListing listing) {
        int window = plugin.rpgConfig().auctionAntiSnipeSeconds();
        if (window <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        long windowMs = window * 1000L;
        if (listing.endsAtMs() - now < windowMs) {
            listing.endsAt(now + windowMs);
            tellWatchers(listing, ChatColor.YELLOW + "[경매] 마감 직전 입찰로 종료가 "
                    + window + "초 연장되었습니다.");
        }
    }

    /** Takes the lot at its buy-now price, ending it immediately. */
    public void buyNow(Player buyer, UUID listingId) {
        AuctionListing listing = listings.get(listingId);
        if (listing == null || listing.settled()) {
            buyer.sendMessage(ChatColor.RED + "[경매] 이미 끝난 경매입니다.");
            return;
        }
        if (!listing.hasBuyNow()) {
            buyer.sendMessage(ChatColor.RED + "[경매] 즉시구매가가 없는 경매입니다. 입찰하세요.");
            return;
        }
        if (listing.seller().equals(buyer.getUniqueId())) {
            buyer.sendMessage(ChatColor.RED + "[경매] 자기 물건은 살 수 없습니다.");
            return;
        }
        int price = listing.buyNowPrice();
        if (!plugin.economy().canAfford(buyer, price)) {
            buyer.sendMessage(ChatColor.RED + "[경매] " + price + " 골드가 필요합니다. (보유 "
                    + plugin.economy().balance(buyer) + ")");
            return;
        }
        // Claimed before any gold or items move: from here nothing else can
        // end this lot, so the rest cannot half-happen.
        if (!listing.settle()) {
            buyer.sendMessage(ChatColor.RED + "[경매] 방금 다른 사람이 가져갔습니다.");
            return;
        }
        if (!plugin.economy().take(buyer, price)) {
            // Balance moved between the check and here. Put the lot back so it
            // is not stranded as settled-but-unsold.
            reopen(listing);
            buyer.sendMessage(ChatColor.RED + "[경매] 골드가 부족합니다.");
            return;
        }

        listings.remove(listing.id());
        // Whoever was leading loses nothing: their gold was held, not spent.
        if (listing.hasBids()) {
            plugin.mailbox().giveGold(listing.topBidder(), listing.topBid(), "즉시구매로 종료, 입찰 반환");
        }
        payOut(listing, buyer.getUniqueId(), buyer.getName(), price, "즉시구매");
        save();
    }

    // -------------------------------------------------------------- ending

    /** Puts a claimed lot back in play; only for a claim that could not finish. */
    private void reopen(AuctionListing listing) {
        listings.remove(listing.id());
        AuctionListing fresh = new AuctionListing(listing.id(), listing.seller(), listing.sellerName(),
                listing.item(), listing.startPrice(), listing.buyNowPrice(),
                listing.createdAtMs(), listing.endsAtMs());
        if (listing.hasBids()) {
            fresh.restoreBid(listing.topBidder(), listing.topBidderName(), listing.topBid());
        }
        listings.put(fresh.id(), fresh);
        save();
    }

    /** Hands the item to the winner and the money, less tax, to the seller. */
    private void payOut(AuctionListing listing, UUID winner, String winnerName, int price, String how) {
        int tax = tax(price);
        int net = price - tax;

        plugin.mailbox().give(winner, listing.item(), "경매 낙찰");
        plugin.mailbox().giveGold(listing.seller(), net, "경매 판매 대금");

        Player buyer = plugin.getServer().getPlayer(winner);
        if (buyer != null && buyer.isOnline()) {
            buyer.sendMessage(ChatColor.GREEN + "[경매] " + ChatColor.WHITE
                    + CollectionService.nameOf(listing.item()) + ChatColor.GRAY + " x"
                    + listing.item().getAmount() + ChatColor.GREEN + " 을(를) "
                    + plugin.economy().format(price) + ChatColor.GREEN + " 에 낙찰받았습니다. (" + how + ")");
            buyer.playSound(buyer.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0F, 1.3F);
        }
        tellSeller(listing, ChatColor.GREEN + "[경매] " + ChatColor.WHITE
                + CollectionService.nameOf(listing.item()) + ChatColor.GREEN + " 이(가) "
                + ChatColor.WHITE + winnerName + ChatColor.GREEN + " 님에게 "
                + plugin.economy().format(price) + ChatColor.GREEN + " 에 팔렸습니다."
                + (tax > 0 ? ChatColor.GRAY + " (수수료 " + tax + " 제외 " + net + " 수령)" : ""));
    }

    private int tax(int price) {
        int percent = plugin.rpgConfig().auctionTaxPercent();
        return percent <= 0 ? 0 : (int) ((long) price * percent / 100L);
    }

    /**
     * The seller takes their lot back. Refused once anyone has bid: a bid is
     * gold already committed, and letting a seller pull the lot after seeing
     * the bidding makes every bid a bet on the seller's mood.
     */
    public void cancel(Player seller, UUID listingId) {
        AuctionListing listing = listings.get(listingId);
        if (listing == null || listing.settled()) {
            seller.sendMessage(ChatColor.RED + "[경매] 이미 끝난 경매입니다.");
            return;
        }
        if (!listing.seller().equals(seller.getUniqueId())) {
            seller.sendMessage(ChatColor.RED + "[경매] 자기가 올린 물건만 내릴 수 있습니다.");
            return;
        }
        if (listing.hasBids()) {
            seller.sendMessage(ChatColor.RED + "[경매] 이미 입찰이 들어와 내릴 수 없습니다. "
                    + ChatColor.GRAY + "(현재 " + plugin.economy().format(listing.topBid()) + ChatColor.GRAY + ")");
            return;
        }
        if (!listing.settle()) {
            seller.sendMessage(ChatColor.RED + "[경매] 방금 다른 사람이 가져갔습니다.");
            return;
        }
        listings.remove(listing.id());
        plugin.mailbox().give(seller.getUniqueId(), listing.item(), "경매 취소");
        save();
        seller.sendMessage(ChatColor.YELLOW + "[경매] 물건을 내렸습니다. "
                + ChatColor.GRAY + "(등록 수수료는 돌려받지 않습니다)");
    }

    /**
     * Closes lots whose time is up; called from the tick pump.
     *
     * Deliberately cheap when there is nothing to do: the common case is a
     * single comparison against the soonest end time, because browse order is
     * end order and nothing here needs a full scan otherwise.
     */
    public void tick() {
        long now = System.currentTimeMillis();
        if (listings.isEmpty() || now < soonestEndMs) {
            return;
        }
        List<AuctionListing> due = null;
        for (AuctionListing listing : listings.values()) {
            if (!listing.settled() && listing.expired(now)) {
                if (due == null) {
                    due = new ArrayList<>();
                }
                due.add(listing);
            }
        }
        if (due == null) {
            // The soonest end is in the past but nothing is actually due -
            // only reachable if the cache went stale, so rebuild it rather
            // than scanning the whole book again next tick, and the tick
            // after that, for as long as the server is up.
            refreshSoonestEnd();
            return;
        }
        for (AuctionListing listing : due) {
            close(listing);
        }
        save();
    }

    private void close(AuctionListing listing) {
        if (!listing.settle()) {
            return;
        }
        listings.remove(listing.id());
        if (listing.hasBids()) {
            payOut(listing, listing.topBidder(), listing.topBidderName(), listing.topBid(), "낙찰");
            return;
        }
        // Nobody bid: the item goes home. The listing fee does not, which is
        // what stops the auction house being free advertising space.
        plugin.mailbox().give(listing.seller(), listing.item(), "경매 유찰");
        tellSeller(listing, ChatColor.YELLOW + "[경매] " + ChatColor.WHITE
                + CollectionService.nameOf(listing.item()) + ChatColor.YELLOW
                + " 이(가) 유찰되어 돌아왔습니다.");
    }

    /**
     * Ends every live lot and returns everything to where it came from.
     * Only for an operator turning the feature off - a normal shutdown leaves
     * lots running, because they are supposed to survive a restart.
     */
    public void refundAll(String reason) {
        for (AuctionListing listing : new ArrayList<>(listings.values())) {
            if (!listing.settle()) {
                continue;
            }
            if (listing.hasBids()) {
                plugin.mailbox().giveGold(listing.topBidder(), listing.topBid(), reason);
            }
            plugin.mailbox().give(listing.seller(), listing.item(), reason);
        }
        listings.clear();
        save();
    }

    // -------------------------------------------------------------- talking

    private void tellSeller(AuctionListing listing, String message) {
        Player seller = plugin.getServer().getPlayer(listing.seller());
        if (seller != null && seller.isOnline()) {
            seller.sendMessage(message);
        }
    }

    private void tellWatchers(AuctionListing listing, String message) {
        tellSeller(listing, message);
        if (listing.hasBids()) {
            Player bidder = plugin.getServer().getPlayer(listing.topBidder());
            if (bidder != null && bidder.isOnline()) {
                bidder.sendMessage(message);
            }
        }
    }

    private void announce(String line, Player except) {
        if (!plugin.rpgConfig().auctionAnnounce()) {
            return;
        }
        for (Player other : plugin.getServer().getOnlinePlayers()) {
            if (!other.equals(except)) {
                other.sendMessage(line);
            }
        }
    }
}
