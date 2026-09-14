package com.rpgcore.plugin.guild;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One guild: who is in it, what it owns, and where it has planted its flag.
 *
 * A guild outlives every session of every member, so it carries its own id and
 * remembers each member's last known name - that is what lets the roster show
 * people who are offline, and what keeps membership attached to the account
 * rather than to a name somebody else may take later.
 *
 * The vault inventory is deliberately created once and held here. Two members
 * opening the vault must be looking at the same container for the same reason
 * a trade uses one shared window: if each viewer got their own copy, two people
 * could take the same stack.
 */
public final class Guild {

    private final UUID id;
    private String name;
    private UUID leader;
    /** Member id -> last known name, in join order. */
    private final Map<UUID, String> members = new LinkedHashMap<>();
    /**
     * The guild's one claim, or null before its flag goes up.
     *
     * One, not a list. A war is won by breaking the enemy's banner, and with
     * two banners "breaking the banner" stops being a single thing that can be
     * won - it becomes a question about which one, and what the other is still
     * protecting after the war is over.
     */
    private GuildClaim claim;
    private final long createdAtMs;

    /**
     * The guild's own gold: deposited by members, spent on flags and wars, and
     * taken whole by whoever wins a war against them.
     *
     * Kept apart from every member's personal balance on purpose. It is what a
     * guild has to lose, which is what makes declaring war on one a decision
     * rather than a formality.
     */
    private int gold;
    /**
     * Gold spent on widening the claim. Not part of {@link #gold} - it has
     * been converted into land and cannot be taken back, which is the whole
     * trade: hoarded gold is at risk in a war, invested gold is not.
     */
    private int invested;

    /**
     * The one shared vault container, built on first use. Null until then, so
     * a server with a hundred guilds does not hold a hundred idle inventories.
     */
    private Inventory vault;
    /**
     * What the vault held at load, kept until somebody opens it.
     *
     * A guild nobody visits this session still has to be written back
     * faithfully on shutdown, and building its inventory just to copy the same
     * stacks out again would defeat the point of building it lazily. So the
     * contents wait here as plain stacks and the save path reads whichever of
     * the two is live.
     */
    private ItemStack[] parked = new ItemStack[0];
    /** True when the vault has changed since it was last written to disk. */
    private boolean vaultDirty;

    Guild(UUID id, String name, UUID leader, String leaderName, long createdAtMs) {
        this.id = id;
        this.name = name;
        this.leader = leader;
        this.createdAtMs = createdAtMs;
        this.members.put(leader, leaderName);
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    void name(String name) {
        this.name = name;
    }

    public UUID leader() {
        return leader;
    }

    void leader(UUID leader) {
        this.leader = leader;
    }

    public boolean isLeader(UUID uuid) {
        return leader.equals(uuid);
    }

    public Set<UUID> members() {
        return Set.copyOf(members.keySet());
    }

    /** Join order, so the roster always reads the same way. */
    public Set<UUID> ordered() {
        return members.keySet();
    }

    public String nameOf(UUID uuid) {
        return members.get(uuid);
    }

    public int size() {
        return members.size();
    }

    public boolean contains(UUID uuid) {
        return uuid != null && members.containsKey(uuid);
    }

    public long createdAtMs() {
        return createdAtMs;
    }

    void add(UUID uuid, String memberName) {
        members.put(uuid, memberName);
    }

    void remove(UUID uuid) {
        members.remove(uuid);
    }

    void refreshName(UUID uuid, String memberName) {
        if (members.containsKey(uuid)) {
            members.put(uuid, memberName);
        }
    }

    /** The guild's claim, or null when its flag is not planted. */
    public GuildClaim claim() {
        return claim;
    }

    public boolean hasClaim() {
        return claim != null;
    }

    void claim(GuildClaim claim) {
        this.claim = claim;
    }

    public int gold() {
        return gold;
    }

    void gold(int gold) {
        this.gold = Math.max(0, gold);
    }

    public int invested() {
        return invested;
    }

    void invested(int invested) {
        this.invested = Math.max(0, invested);
    }

    // ----------------------------------------------------------------- vault

    /** The live vault, or null when it has never been opened this session. */
    Inventory vaultOrNull() {
        return vault;
    }

    void vault(Inventory vault) {
        this.vault = vault;
        // The inventory is now the truth; the parked copy would only go stale.
        this.parked = new ItemStack[0];
    }

    ItemStack[] parkedVault() {
        return parked;
    }

    void parkedVault(ItemStack[] parked) {
        this.parked = parked;
    }

    public boolean vaultDirty() {
        return vaultDirty;
    }

    void markVaultDirty() {
        this.vaultDirty = true;
    }

    void clearVaultDirty() {
        this.vaultDirty = false;
    }
}
