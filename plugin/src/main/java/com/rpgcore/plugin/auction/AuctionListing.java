package com.rpgcore.plugin.auction;

import org.bukkit.ChatColor;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * One lot on the auction house.
 *
 * The item held here is the only copy: it was taken out of the seller's hand
 * when the lot was created and it does not exist anywhere else until the lot
 * settles. The same is true of {@link #topBid()} - that gold was taken from
 * the bidder when they bid, so the balance shown to everyone is already the
 * balance they have left, and an outbid refund is a real refund rather than
 * gold being minted.
 *
 * {@link #settled()} is the flag that makes every ending run exactly once.
 * Buying, expiring and cancelling all go through it, so two clicks landing in
 * the same tick cannot sell the same lot twice.
 */
public final class AuctionListing {

    private final UUID id;
    private final UUID seller;
    private final String sellerName;
    private final ItemStack item;
    private final int startPrice;
    /** 0 when the lot has no buy-now price and can only be bid on. */
    private final int buyNowPrice;
    private final long createdAtMs;

    private UUID topBidder;
    private String topBidderName;
    /** 0 until someone bids; then the gold currently held from that bidder. */
    private int topBid;
    private long endsAtMs;
    private boolean settled;

    AuctionListing(UUID id, UUID seller, String sellerName, ItemStack item,
                   int startPrice, int buyNowPrice, long createdAtMs, long endsAtMs) {
        this.id = id;
        this.seller = seller;
        this.sellerName = sellerName;
        this.item = item;
        this.startPrice = startPrice;
        this.buyNowPrice = buyNowPrice;
        this.createdAtMs = createdAtMs;
        this.endsAtMs = endsAtMs;
    }

    public UUID id() {
        return id;
    }

    public UUID seller() {
        return seller;
    }

    public String sellerName() {
        return sellerName;
    }

    /** The escrowed stack. Callers must clone before handing it to anyone. */
    public ItemStack item() {
        return item;
    }

    public int startPrice() {
        return startPrice;
    }

    public int buyNowPrice() {
        return buyNowPrice;
    }

    public boolean hasBuyNow() {
        return buyNowPrice > 0;
    }

    public UUID topBidder() {
        return topBidder;
    }

    public String topBidderName() {
        return topBidderName;
    }

    public int topBid() {
        return topBid;
    }

    public boolean hasBids() {
        return topBidder != null;
    }

    public long createdAtMs() {
        return createdAtMs;
    }

    public long endsAtMs() {
        return endsAtMs;
    }

    public boolean expired(long nowMs) {
        return nowMs >= endsAtMs;
    }

    public boolean settled() {
        return settled;
    }

    /** One-way: returns false when this lot has already ended some other way. */
    boolean settle() {
        if (settled) {
            return false;
        }
        settled = true;
        return true;
    }

    void bid(UUID bidder, String bidderName, int amount) {
        this.topBidder = bidder;
        this.topBidderName = bidderName;
        this.topBid = amount;
    }

    void restoreBid(UUID bidder, String bidderName, int amount) {
        bid(bidder, bidderName, amount);
    }

    void endsAt(long endsAtMs) {
        this.endsAtMs = endsAtMs;
    }

    /** What it currently costs to take the lot by bidding, ignoring buy-now. */
    public int currentPrice() {
        return hasBids() ? topBid : startPrice;
    }

    /** "2시간 13분", or "곧 종료" once it is under a minute. */
    public String remaining(long nowMs) {
        long seconds = Math.max(0L, (endsAtMs - nowMs) / 1000L);
        if (seconds < 60L) {
            return ChatColor.RED + "곧 종료";
        }
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        if (hours > 0L) {
            return hours + "시간 " + minutes + "분";
        }
        return minutes + "분";
    }
}
