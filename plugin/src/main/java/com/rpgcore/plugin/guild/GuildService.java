package com.rpgcore.plugin.guild;

import com.rpgcore.plugin.RpgCorePlugin;
import com.rpgcore.plugin.util.DeferredSave;
import com.rpgcore.plugin.util.HandOver;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Guilds: membership, the shared vault, and claimed land.
 *
 * Every mutation goes through here so the three indexes - guild by id, guild
 * by member, and the chunk-keyed claim lookup - cannot drift apart, and so
 * every change is written back to guilds.yml from one place.
 *
 * Guilds are permanent by design. A party is a thing you form for an evening;
 * a guild holds a vault full of other people's items and land other people
 * cannot build on, so it ends only when somebody says so.
 */
public final class GuildService {

    private final RpgCorePlugin plugin;
    private final GuildStorage storage;
    private final Map<UUID, Guild> byId = new LinkedHashMap<>();
    private final Map<UUID, Guild> byMember = new ConcurrentHashMap<>();
    private final ClaimIndex claims = new ClaimIndex();
    /** invited player -> (inviter -> expiry millis). */
    private final Map<UUID, Map<UUID, Long>> invites = new ConcurrentHashMap<>();
    /** Where the rolling banner check got to; see validateOneClaim. */
    private int validationCursor;
    /** Live and recently finished wars, keyed by nothing - there are few. */
    private final List<GuildWar> wars = new ArrayList<>();
    /** guild pair -> when they may fight again. Keyed both ways round. */
    private final Map<String, Long> warCooldowns = new HashMap<>();

    private final DeferredSave writer;

    public GuildService(RpgCorePlugin plugin) {
        this.plugin = plugin;
        this.storage = new GuildStorage(plugin);
        this.writer = new DeferredSave(plugin, plugin.saveQueue(), "guilds.yml",
                () -> storage.build(byId.values(), wars, activeCooldowns()));
    }

    public boolean enabled() {
        return plugin.rpgConfig().guildEnabled();
    }

    public void load() {
        byId.clear();
        byMember.clear();
        claims.clear();
        for (GuildStorage.Loaded loaded : storage.load()) {
            Guild guild = loaded.guild();
            byId.put(guild.id(), guild);
            for (UUID member : guild.members()) {
                byMember.put(member, guild);
            }
            if (guild.hasClaim()) {
                // Radius is derived from what the guild has invested rather
                // than trusted from the file, so a change to the base radius
                // or to the price of a block takes effect on reload instead of
                // leaving every existing claim on yesterday's numbers.
                guild.claim().radius(radiusFor(guild));
                claims.add(guild.claim());
            }
            parkVault(guild, loaded);
        }
        wars.clear();
        warCooldowns.clear();
        for (Map.Entry<String, Long> cooldown : storage.loadCooldowns().entrySet()) {
            restoreCooldown(cooldown.getKey(), cooldown.getValue());
        }
        for (GuildWar war : storage.loadWars()) {
            // A war whose guilds did not survive the file has nothing to be
            // about; dropping it here keeps every later lookup simple.
            if (byId.containsKey(war.attacker()) && byId.containsKey(war.defender())) {
                wars.add(war);
            }
        }
        plugin.getLogger().info("Guilds loaded: " + byId.size() + " with "
                + claims.size() + " claim(s), " + liveWars().size() + " war(s) running.");
    }

    /** Holds a loaded vault as plain stacks until somebody opens it. */
    private void parkVault(Guild guild, GuildStorage.Loaded loaded) {
        if (loaded.vault().isEmpty()) {
            return;
        }
        int size = vaultSize();
        ItemStack[] parked = new ItemStack[size];
        for (int i = 0; i < loaded.vault().size(); i++) {
            int slot = loaded.vaultSlots().get(i);
            if (slot >= 0 && slot < size) {
                parked[slot] = loaded.vault().get(i);
            } else {
                // The vault was made smaller in config since this was written.
                // Handing the stack back beats leaving it in a slot nobody can
                // ever click on.
                plugin.mailbox().hold(guild.leader(), loaded.vault().get(i),
                        "길드 보관함 축소로 반환");
                plugin.getLogger().warning("Guild " + guild.name() + " had a stack in slot "
                        + slot + ", beyond the configured vault size of " + size
                        + "; it was sent to the leader's mailbox.");
            }
        }
        guild.parkedVault(parked);
    }

    /**
     * Marks the file stale. The write itself is coalesced and moved off the
     * main thread - a guild file with full vaults costs tens of milliseconds
     * to turn into YAML, and this used to be paid on the spot by every deposit,
     * every vault close and every membership change.
     */
    public void save() {
        writer.markDirty();
    }

    /** Builds and writes on this thread. For shutdown only. */
    public void saveNow() {
        writer.flushNow();
    }

    public void flushIfDirty() {
        writer.flushIfDirty();
    }

    public int count() {
        return byId.size();
    }

    public int claimCount() {
        return claims.size();
    }

    /** Radius a guild's borders reach, base plus whatever it has invested. */
    public int radiusFor(Guild guild) {
        int base = plugin.rpgConfig().guildClaimRadius();
        int perBlock = plugin.rpgConfig().guildInvestPerBlock();
        int grown = perBlock <= 0 ? 0 : guild.invested() / perBlock;
        return Math.clamp(base + grown, base, plugin.rpgConfig().guildMaxRadius());
    }

    /** Gold that would buy the next block of radius, or 0 when at the cap. */
    public int nextRadiusCost(Guild guild) {
        int perBlock = plugin.rpgConfig().guildInvestPerBlock();
        if (perBlock <= 0 || radiusFor(guild) >= plugin.rpgConfig().guildMaxRadius()) {
            return 0;
        }
        return perBlock - (guild.invested() % perBlock);
    }

    public Guild guildOf(Player player) {
        return player == null ? null : byMember.get(player.getUniqueId());
    }

    public Guild guildOf(UUID uuid) {
        return uuid == null ? null : byMember.get(uuid);
    }

    public Guild byId(UUID id) {
        return id == null ? null : byId.get(id);
    }

    public Guild byName(String name) {
        for (Guild guild : byId.values()) {
            if (guild.name().equalsIgnoreCase(name)) {
                return guild;
            }
        }
        return null;
    }

    public List<Guild> all() {
        return List.copyOf(byId.values());
    }

    public boolean sameGuild(Player a, Player b) {
        Guild guild = guildOf(a);
        return guild != null && guild.contains(b.getUniqueId());
    }

    // ------------------------------------------------------------ membership

    public Guild create(Player leader, String requestedName) {
        if (!enabled()) {
            leader.sendMessage(ChatColor.RED + "[길드] 이 서버에서는 길드를 쓸 수 없습니다.");
            return null;
        }
        if (guildOf(leader) != null) {
            leader.sendMessage(ChatColor.RED + "[길드] 이미 길드에 속해 있습니다.");
            return null;
        }
        String name = sanitiseName(leader, requestedName);
        if (name == null) {
            return null;
        }
        int cost = plugin.rpgConfig().guildCreateCost();
        if (cost > 0 && !plugin.economy().take(leader, cost)) {
            leader.sendMessage(ChatColor.RED + "[길드] 창설 비용 " + cost + " 골드가 부족합니다. (보유 "
                    + plugin.economy().balance(leader) + ")");
            return null;
        }

        Guild guild = new Guild(UUID.randomUUID(), name, leader.getUniqueId(),
                leader.getName(), System.currentTimeMillis());
        byId.put(guild.id(), guild);
        byMember.put(leader.getUniqueId(), guild);
        save();

        leader.sendMessage(ChatColor.GREEN + "[길드] '" + ChatColor.WHITE + name + ChatColor.GREEN
                + "' 길드를 창설했습니다." + (cost > 0 ? ChatColor.GRAY + " (-" + cost + " 골드)" : ""));
        leader.sendMessage(ChatColor.GRAY + "  /guild invite <플레이어> 로 사람을 부르고, "
                + ChatColor.YELLOW + "/guild banner" + ChatColor.GRAY + " 로 영지 깃발을 받으세요.");
        return guild;
    }

    private String sanitiseName(Player player, String requested) {
        if (requested == null || requested.isBlank()) {
            player.sendMessage(ChatColor.RED + "[길드] 이름을 입력하세요.");
            return null;
        }
        String name = ChatColor.stripColor(requested.replace('§', '&')).trim();
        int max = plugin.rpgConfig().guildNameMaxLength();
        if (name.isEmpty()) {
            player.sendMessage(ChatColor.RED + "[길드] 쓸 수 없는 이름입니다.");
            return null;
        }
        if (name.length() > max) {
            player.sendMessage(ChatColor.RED + "[길드] 이름은 " + max + "자까지입니다.");
            return null;
        }
        for (Guild other : byId.values()) {
            if (!other.contains(player.getUniqueId()) && other.name().equalsIgnoreCase(name)) {
                player.sendMessage(ChatColor.RED + "[길드] 이미 쓰이는 이름입니다: " + name);
                return null;
            }
        }
        return name;
    }

    public void invite(Player inviter, Player target) {
        Guild guild = requireLeader(inviter, "초대");
        if (guild == null) {
            return;
        }
        if (inviter.equals(target)) {
            inviter.sendMessage(ChatColor.RED + "[길드] 자기 자신은 초대할 수 없습니다.");
            return;
        }
        if (guildOf(target) != null) {
            inviter.sendMessage(ChatColor.RED + "[길드] 그 플레이어는 이미 길드에 속해 있습니다.");
            return;
        }
        int max = plugin.rpgConfig().guildMaxMembers();
        if (guild.size() >= max) {
            inviter.sendMessage(ChatColor.RED + "[길드] 길드 정원(" + max + "명)이 찼습니다.");
            return;
        }

        long expiry = System.currentTimeMillis() + plugin.rpgConfig().guildInviteSeconds() * 1000L;
        invites.computeIfAbsent(target.getUniqueId(), k -> new HashMap<>())
                .put(inviter.getUniqueId(), expiry);

        inviter.sendMessage(ChatColor.GREEN + "[길드] " + target.getName() + " 을(를) 초대했습니다.");
        target.sendMessage(ChatColor.GOLD + "[길드] " + inviter.getName() + " 이(가) '"
                + ChatColor.WHITE + guild.name() + ChatColor.GOLD + "' 길드에 초대했습니다. "
                + ChatColor.YELLOW + "/guild accept " + inviter.getName()
                + ChatColor.GRAY + " (" + plugin.rpgConfig().guildInviteSeconds() + "초 안에)");
    }

    public void accept(Player player, String inviterName) {
        Map<UUID, Long> pending = invites.get(player.getUniqueId());
        purgeExpired(pending);
        if (pending == null || pending.isEmpty()) {
            player.sendMessage(ChatColor.RED + "[길드] 받은 초대가 없습니다.");
            return;
        }
        if (guildOf(player) != null) {
            player.sendMessage(ChatColor.RED + "[길드] 이미 길드에 속해 있습니다. 먼저 /guild leave 하세요.");
            return;
        }

        UUID inviter = resolveInviter(player, pending, inviterName);
        if (inviter == null) {
            return;
        }
        Guild guild = byMember.get(inviter);
        if (guild == null) {
            player.sendMessage(ChatColor.RED + "[길드] 그 길드는 더 이상 없습니다.");
            pending.remove(inviter);
            return;
        }
        if (guild.size() >= plugin.rpgConfig().guildMaxMembers()) {
            player.sendMessage(ChatColor.RED + "[길드] 길드 정원이 찼습니다.");
            return;
        }

        pending.remove(inviter);
        guild.add(player.getUniqueId(), player.getName());
        byMember.put(player.getUniqueId(), guild);
        save();
        broadcast(guild, ChatColor.GREEN + "[길드] " + player.getName() + " 이(가) 길드에 들어왔습니다. ("
                + guild.size() + "/" + plugin.rpgConfig().guildMaxMembers() + ")");
    }

    private UUID resolveInviter(Player player, Map<UUID, Long> pending, String inviterName) {
        if (inviterName == null) {
            if (pending.size() > 1) {
                player.sendMessage(ChatColor.RED + "[길드] 초대가 여러 개입니다. /guild accept <플레이어> 로 지정하세요.");
                return null;
            }
            return pending.keySet().iterator().next();
        }
        for (UUID uuid : pending.keySet()) {
            Player candidate = plugin.getServer().getPlayer(uuid);
            if (candidate != null && candidate.getName().equalsIgnoreCase(inviterName)) {
                return uuid;
            }
        }
        player.sendMessage(ChatColor.RED + "[길드] " + inviterName + " 에게 받은 초대가 없습니다.");
        return null;
    }

    public void deny(Player player) {
        Map<UUID, Long> pending = invites.remove(player.getUniqueId());
        if (pending == null || pending.isEmpty()) {
            player.sendMessage(ChatColor.RED + "[길드] 받은 초대가 없습니다.");
            return;
        }
        player.sendMessage(ChatColor.YELLOW + "[길드] 초대를 거절했습니다.");
    }

    public void leave(Player player) {
        Guild guild = guildOf(player);
        if (guild == null) {
            player.sendMessage(ChatColor.RED + "[길드] 길드에 속해 있지 않습니다.");
            return;
        }
        if (guild.isLeader(player.getUniqueId()) && guild.size() > 1) {
            player.sendMessage(ChatColor.RED + "[길드] 길드장은 나갈 수 없습니다. "
                    + "/guild transfer <플레이어> 로 넘기거나 /guild disband 로 해체하세요.");
            return;
        }
        if (guild.size() == 1) {
            disband(player);
            return;
        }
        closeVault(player);
        guild.remove(player.getUniqueId());
        byMember.remove(player.getUniqueId());
        save();
        player.sendMessage(ChatColor.YELLOW + "[길드] 길드에서 나왔습니다.");
        broadcast(guild, ChatColor.YELLOW + "[길드] " + player.getName() + " 이(가) 길드를 떠났습니다.");
    }

    public void kick(Player leader, String targetName) {
        Guild guild = requireLeader(leader, "추방");
        if (guild == null) {
            return;
        }
        for (UUID uuid : guild.members()) {
            if (!targetName.equalsIgnoreCase(guild.nameOf(uuid))) {
                continue;
            }
            if (guild.isLeader(uuid)) {
                leader.sendMessage(ChatColor.RED + "[길드] 자기 자신은 추방할 수 없습니다.");
                return;
            }
            Player member = plugin.getServer().getPlayer(uuid);
            if (member != null && member.isOnline()) {
                // Out of the vault before they are out of the guild, or they
                // would keep shopping in a container they no longer belong to.
                closeVault(member);
                member.sendMessage(ChatColor.RED + "[길드] 길드에서 추방되었습니다.");
            }
            guild.remove(uuid);
            byMember.remove(uuid);
            save();
            broadcast(guild, ChatColor.YELLOW + "[길드] " + targetName + " 이(가) 추방되었습니다.");
            return;
        }
        leader.sendMessage(ChatColor.RED + "[길드] 길드에 그런 플레이어가 없습니다: " + targetName);
    }

    public void transfer(Player leader, String targetName) {
        Guild guild = requireLeader(leader, "양도");
        if (guild == null) {
            return;
        }
        for (UUID uuid : guild.members()) {
            if (targetName.equalsIgnoreCase(guild.nameOf(uuid)) && !guild.isLeader(uuid)) {
                guild.leader(uuid);
                save();
                broadcast(guild, ChatColor.GOLD + "[길드] " + guild.nameOf(uuid)
                        + " 이(가) 새 길드장이 되었습니다.");
                return;
            }
        }
        leader.sendMessage(ChatColor.RED + "[길드] 길드에 그런 플레이어가 없습니다: " + targetName);
    }

    /**
     * Ends a guild. The vault goes to the leader's mailbox rather than being
     * dropped: it is the only copy of everything the members put in it.
     */
    public void disband(Player leader) {
        Guild guild = guildOf(leader);
        if (guild == null) {
            leader.sendMessage(ChatColor.RED + "[길드] 길드에 속해 있지 않습니다.");
            return;
        }
        if (!guild.isLeader(leader.getUniqueId())) {
            leader.sendMessage(ChatColor.RED + "[길드] 길드장만 해체할 수 있습니다.");
            return;
        }

        // Settled before a single coin moves: the prize is what the treasury
        // holds now, not what is left after the losing side empties it.
        forfeitWarIfAny(guild, guild.name() + " 이(가) 길드를 해체함");

        int returned = emptyVaultTo(guild, guild.leader(), "길드 해체");
        if (guild.hasClaim()) {
            GuildClaim claim = guild.claim();
            claims.remove(claim);
            guild.claim(null);
            returnBanner(claim, guild.leader());
        }
        // A backstop for the one case the forfeit above cannot settle: a war
        // whose other side is no longer in the book. Normally this finds
        // nothing, because disbanding mid-war has already been paid for.
        endWarsInvolving(guild.id(), "상대 길드가 해체되었습니다.");
        // Treasury and invested gold go back to the leader rather than
        // evaporating - disbanding is not a way to destroy a guild's savings.
        int treasury = guild.gold();
        if (treasury > 0) {
            plugin.mailbox().giveGold(guild.leader(), treasury, "길드 해체 - 금고 반환");
            guild.gold(0);
        }
        broadcast(guild, ChatColor.YELLOW + "[길드] '" + ChatColor.WHITE + guild.name()
                + ChatColor.YELLOW + "' 길드가 해체되었습니다.");
        for (UUID uuid : guild.members()) {
            Player member = plugin.getServer().getPlayer(uuid);
            if (member != null && member.isOnline()) {
                closeVault(member);
            }
            byMember.remove(uuid);
        }
        byId.remove(guild.id());
        save();
        if (returned > 0) {
            leader.sendMessage(ChatColor.YELLOW + "[길드] 보관함에 있던 " + returned
                    + "개는 우편함으로 보냈습니다.");
        }
    }

    private Guild requireLeader(Player player, String what) {
        Guild guild = guildOf(player);
        if (guild == null) {
            player.sendMessage(ChatColor.RED + "[길드] 길드에 속해 있지 않습니다.");
            return null;
        }
        if (!guild.isLeader(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "[길드] 길드장만 " + what + "할 수 있습니다.");
            return null;
        }
        return guild;
    }

    /**
     * Drops what a leaving player was holding open.
     *
     * Every other invite list in the plugin is cleared on quit; this one was
     * not, so an invitation nobody ever answered stayed in the map for the
     * life of the server. Expiry only ever ran when the target typed /guild
     * accept, which is exactly what someone who logged off never does.
     */
    public void handleQuit(Player player) {
        invites.remove(player.getUniqueId());
    }

    /** Keeps the stored name current, in case the player renamed. */
    public void handleJoin(Player player) {
        Guild guild = guildOf(player);
        if (guild == null) {
            return;
        }
        if (!player.getName().equals(guild.nameOf(player.getUniqueId()))) {
            guild.refreshName(player.getUniqueId(), player.getName());
            save();
        }
    }

    // ----------------------------------------------------------------- vault

    public int vaultSize() {
        return plugin.rpgConfig().guildVaultRows() * 9;
    }

    /**
     * Opens the guild's one shared vault. Two members with it open are two
     * viewers of the same container, exactly as they would be at a chest -
     * which is the only arrangement in which they cannot both take the same
     * stack.
     */
    public void openVault(Player player) {
        Guild guild = guildOf(player);
        if (guild == null) {
            player.sendMessage(ChatColor.RED + "[길드] 길드에 속해 있지 않습니다.");
            return;
        }
        player.openInventory(vaultOf(guild));
    }

    Inventory vaultOf(Guild guild) {
        Inventory vault = guild.vaultOrNull();
        if (vault != null) {
            return vault;
        }
        GuildVaultHolder holder = new GuildVaultHolder(guild.id());
        vault = plugin.getServer().createInventory(holder, vaultSize(),
                ChatColor.translateAlternateColorCodes('&',
                        plugin.rpgConfig().guildVaultTitle().replace("%guild%", guild.name())));
        holder.setInventory(vault);
        ItemStack[] parked = guild.parkedVault();
        for (int slot = 0; slot < parked.length && slot < vault.getSize(); slot++) {
            vault.setItem(slot, parked[slot]);
        }
        guild.vault(vault);
        return vault;
    }

    /** Called when a viewer closes the vault; that is when it is written out. */
    public void handleVaultClosed(UUID guildId, Player viewer) {
        if (!byId.containsKey(guildId)) {
            return;
        }
        save();
        // A vault session moves items both ways, so no ordering is safe for
        // both; what matters is that the guild file and the player file stop
        // disagreeing within milliseconds instead of minutes.
        HandOver.takenFromPlayer(viewer, writer);
    }

    private void closeVault(Player player) {
        if (player.getOpenInventory().getTopInventory().getHolder() instanceof GuildVaultHolder) {
            player.closeInventory();
        }
    }

    /** Empties the vault into somebody's mailbox; returns how many stacks. */
    private int emptyVaultTo(Guild guild, UUID owner, String note) {
        Inventory vault = guild.vaultOrNull();
        int moved = 0;
        if (vault != null) {
            for (ItemStack stack : vault.getContents()) {
                if (stack != null && !stack.getType().isAir()) {
                    plugin.mailbox().hold(owner, stack, note);
                    moved++;
                }
            }
            vault.clear();
        } else {
            for (ItemStack stack : guild.parkedVault()) {
                if (stack != null && !stack.getType().isAir()) {
                    plugin.mailbox().hold(owner, stack, note);
                    moved++;
                }
            }
        }
        guild.parkedVault(new ItemStack[0]);
        return moved;
    }

    // ---------------------------------------------------------------- claims

    /**
     * True when blasts must be kept off this block.
     *
     * A guild that is actually fighting a war loses its blast shield, so TNT
     * works as a siege weapon on land that is already open to enemy pickaxes.
     * Outside a war the shield always holds - otherwise "outsiders cannot
     * destroy blocks here" would only mean "outsiders cannot destroy blocks
     * here by hand".
     */
    public boolean shieldedFromBlasts(World world, int x, int z) {
        GuildClaim claim = claimAt(world, x, z);
        if (claim == null) {
            return false;
        }
        GuildWar war = warOf(claim.guildId());
        return war == null || !war.fighting(System.currentTimeMillis());
    }

    /** The claim covering this location, or null. Hot path: chunk-indexed. */
    public GuildClaim claimAt(Location location) {
        World world = location.getWorld();
        return world == null ? null
                : claimAt(world, location.getBlockX(), location.getBlockZ());
    }

    public GuildClaim claimAt(World world, int x, int z) {
        // The toggle is honoured here rather than at registration, so
        // features.guilds responds to /rpgcore reload like every other one.
        // It matters most on this path: with the feature off, land would
        // otherwise still be protected and buffed while every command that
        // could unclaim it was refused.
        return enabled() ? claims.at(world, x, z) : null;
    }

    /**
     * True when this player may change blocks here.
     *
     * Three ways to be allowed: there is no claim, the claim is their guild's,
     * or the two guilds are in a war that has actually started. Ops are always
     * allowed, so an administrator is never locked out of their own server.
     *
     * The war case is the only one that lets a player break another guild's
     * land, and it is deliberately narrow: a declared war that is still in its
     * preparation window grants nothing, because the point of that window is
     * that the defenders get to be there for it.
     */
    public boolean mayBuild(Player player, Location location) {
        GuildClaim claim = claimAt(location);
        if (claim == null || player.hasPermission("rpgcore.admin")) {
            return true;
        }
        Guild owner = byId.get(claim.guildId());
        if (owner == null) {
            return true;
        }
        if (owner.contains(player.getUniqueId())) {
            return true;
        }
        Guild theirs = byMember.get(player.getUniqueId());
        if (theirs == null) {
            return false;
        }
        GuildWar war = warBetween(theirs.id(), owner.id());
        return war != null && war.fighting(System.currentTimeMillis());
    }

    /**
     * Why a player was refused, in words they can act on. "This is somebody
     * else's land" and "the war has not started yet" are different problems
     * and one of them is about to stop being one.
     */
    public String refusalFor(Player player, Location location) {
        GuildClaim claim = claimAt(location);
        if (claim == null) {
            return null;
        }
        Guild owner = byId.get(claim.guildId());
        if (owner == null) {
            return null;
        }
        Guild theirs = byMember.get(player.getUniqueId());
        GuildWar war = theirs == null ? null : warBetween(theirs.id(), owner.id());
        if (war != null && war.phase(System.currentTimeMillis()) == GuildWar.Phase.PREPARING) {
            return "교전 시작까지 " + war.remaining(System.currentTimeMillis()) + " 남았습니다.";
        }
        return owner.name() + " 길드의 영지입니다.";
    }

    /**
     * A banner came down while its guild was in a war.
     *
     * Two ways that ends a war, and both have to be handled here or the other
     * becomes the way around it:
     *
     *   - an enemy broke it, during the fighting. That is the win condition.
     *   - the owners broke it themselves. That is a forfeit, at any point in
     *     a war including the preparation window. Without this rule the losing
     *     move is to knock down your own flag as the enemy approaches: the
     *     objective disappears, the war runs out as a draw, and the treasury
     *     that was at stake is never at stake again.
     *
     * Returns true when this settled a war, which tells the caller the claim
     * is already down and not to treat it as an ordinary break.
     */
    /**
     * A claim banner destroyed by something that is not a player: TNT, a
     * creeper, fire.
     *
     * This matters most during a war, which is exactly when it can happen -
     * the blast shield is dropped on purpose while a war is running, so
     * explosives are the siege weapon the feature invites. Without this, the
     * obvious way to attack a flag destroys the block, leaves the claim
     * registered with nothing standing on it, and does not win the war.
     *
     * A war has two sides, so the winner is unambiguous even though nobody
     * knows who lit the fuse: the flag that fell belongs to one of them.
     */
    public void handleBannerDestroyed(GuildClaim claim) {
        Guild owner = byId.get(claim.guildId());
        if (owner == null) {
            claims.remove(claim);
            return;
        }
        GuildWar war = warOf(owner.id());
        owner.claim(null);
        claims.remove(claim);

        if (war != null && war.fighting(System.currentTimeMillis())) {
            Guild enemy = byId.get(war.opponentOf(owner.id()));
            if (enemy != null) {
                winWar(war, enemy, owner, owner.name() + " 의 깃발이 폭발로 무너짐");
                return;
            }
        }
        broadcast(owner, ChatColor.YELLOW + "[영지] " + claim.describe()
                + " 의 깃발이 파괴되어 영지가 사라졌습니다.");
        save();
    }

    public boolean handleBannerBreak(Player breaker, GuildClaim claim) {
        Guild owner = byId.get(claim.guildId());
        if (owner == null) {
            return false;
        }
        Guild theirs = byMember.get(breaker.getUniqueId());
        long now = System.currentTimeMillis();

        if (theirs != null && !owner.id().equals(theirs.id())) {
            GuildWar war = warBetween(theirs.id(), owner.id());
            if (war == null || !war.fighting(now)) {
                return false;
            }
            // The flag comes down with the guild that lost it, before the
            // prize moves, so a second breaker in the same tick finds no claim.
            owner.claim(null);
            claims.remove(claim);
            winWar(war, theirs, owner, breaker.getName() + " 이(가) 깃발을 무너뜨림");
            return true;
        }

        // Their own flag, and they are in a war: that is a surrender.
        if (warOf(owner.id()) == null) {
            return false;
        }
        owner.claim(null);
        claims.remove(claim);
        forfeitWarIfAny(owner, owner.name() + " 이(가) 스스로 깃발을 내림");
        return true;
    }

    /**
     * Registers a claim at a newly planted banner, or explains why not.
     * Returns false when the banner should stay in the player's hand.
     */
    public boolean plantBanner(Player player, Location bannerBlock) {
        Guild guild = guildOf(player);
        if (guild == null) {
            player.sendMessage(ChatColor.RED + "[영지] 길드에 속해 있지 않습니다.");
            return false;
        }
        if (!guild.isLeader(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "[영지] 길드장만 영지를 선포할 수 있습니다.");
            return false;
        }
        if (guild.hasClaim()) {
            player.sendMessage(ChatColor.RED + "[영지] 길드의 영지는 한 곳뿐입니다. "
                    + ChatColor.GRAY + "(현재 " + guild.claim().describe() + ")");
            player.sendMessage(ChatColor.GRAY + "  옮기려면 기존 깃발을 먼저 부수세요.");
            return false;
        }
        if (atWar(guild.id())) {
            // Re-planting mid-war would let a losing guild move the objective
            // out from under an army that is already marching at it.
            player.sendMessage(ChatColor.RED + "[영지] 전쟁 중에는 깃발을 세울 수 없습니다.");
            return false;
        }
        World world = bannerBlock.getWorld();
        if (world == null) {
            return false;
        }

        GuildClaim proposed = new GuildClaim(guild.id(), world.getUID(),
                bannerBlock.getBlockX(), bannerBlock.getBlockY(), bannerBlock.getBlockZ(),
                radiusFor(guild));
        GuildClaim clash = firstOverlap(proposed);
        if (clash != null) {
            Guild other = byId.get(clash.guildId());
            player.sendMessage(ChatColor.RED + "[영지] 너무 가깝습니다. "
                    + (other == null ? "다른 영지" : "'" + other.name() + "' 영지")
                    + " 와(과) 거리를 두어야 합니다.");
            return false;
        }

        guild.claim(proposed);
        claims.add(proposed);
        save();

        player.sendMessage(ChatColor.GREEN + "[영지] 영지를 선포했습니다. "
                + ChatColor.GRAY + proposed.describe());
        broadcast(guild, ChatColor.GREEN + "[영지] " + player.getName() + " 이(가) "
                + proposed.describe() + " 에 영지를 세웠습니다.");
        return true;
    }

    /**
     * Two claims may not overlap, and must keep a gap beyond that - touching
     * borders make "whose land is this" a question about a single block, which
     * is exactly the argument claims exist to prevent.
     *
     * Judged at the largest radius a claim can ever reach, not the radius it
     * has today. Borders grow when a guild invests, and spacing flags by their
     * current size would let a guild buy its way into a neighbour's land - or,
     * worse, make an investment they had already paid for impossible to apply.
     * Spacing for the maximum once means growth never has to be refused.
     */
    private GuildClaim firstOverlap(GuildClaim proposed) {
        int gap = plugin.rpgConfig().guildClaimGap();
        int reach = plugin.rpgConfig().guildMaxRadius();
        double required = reach + reach + gap;
        for (GuildClaim existing : claims.allIn(proposed.worldId())) {
            double distance = existing.centreDistance(proposed);
            if (distance >= 0 && distance < required) {
                return existing;
            }
        }
        return null;
    }

    /** Removes the claim whose banner block was just broken, if there is one. */
    public GuildClaim removeBannerAt(World world, int x, int y, int z) {
        GuildClaim claim = claims.at(world, x, z);
        if (claim == null || !claim.isBannerAt(world, x, y, z)) {
            return null;
        }
        Guild guild = byId.get(claim.guildId());
        if (guild != null) {
            guild.claim(null);
            broadcast(guild, ChatColor.YELLOW + "[영지] " + claim.describe() + " 의 영지가 사라졌습니다.");
        }
        claims.remove(claim);
        save();
        return claim;
    }

    /**
     * Takes a claim's banner off the map and posts it back to its owner.
     *
     * A banner cost the guild real gold, so deleting it along with the claim
     * would be charging them for the privilege of disbanding. The block is
     * only touched if its world and chunk are already loaded - a disband must
     * not drag terrain into memory to tidy up a flag nobody is looking at, and
     * the claim is gone either way.
     */
    private void returnBanner(GuildClaim claim, UUID owner) {
        World world = plugin.getServer().getWorld(claim.worldId());
        if (world == null || !world.isChunkLoaded(claim.x() >> 4, claim.z() >> 4)) {
            return;
        }
        org.bukkit.block.Block block = world.getBlockAt(claim.x(), claim.y(), claim.z());
        if (!block.getType().name().endsWith("BANNER")) {
            return;
        }
        ItemStack banner = plugin.claimListener().claimBanner(block.getType());
        block.setType(org.bukkit.Material.AIR);
        plugin.mailbox().hold(owner, banner, "길드 해체로 회수한 영지 깃발");
    }

    /**
     * Checks one claim's banner is still standing, and drops the claim if it
     * is not.
     *
     * A banner can leave the world in ways no listener here covers - a piston,
     * a world edit, an operator clearing an area - and a claim whose flag is
     * gone is the worst kind of bug: land that is still protected and still
     * buffed with nothing on it to explain why, and nothing to break to undo
     * it. Rather than try to enumerate every way a block can vanish, one claim
     * is checked per pass, so every claim is re-checked regularly and the cost
     * is one block read per cycle no matter how many claims there are.
     *
     * Claims in unloaded chunks are skipped rather than dropped: the block is
     * unreadable there, and nobody is being kept out of land nobody is near.
     */
    public void validateOneClaim() {
        if (byId.isEmpty()) {
            return;
        }
        // Walked rather than collected. This runs on the HUD interval forever,
        // and building a list of every claim just to index one of them made
        // the idle cost of the feature proportional to the number of guilds.
        GuildClaim claim = null;
        int seen = 0;
        int wanted = validationCursor;
        for (Guild guild : byId.values()) {
            if (!guild.hasClaim()) {
                continue;
            }
            if (seen == wanted) {
                claim = guild.claim();
            }
            seen++;
        }
        if (seen == 0) {
            validationCursor = 0;
            return;
        }
        validationCursor = (wanted + 1) % seen;
        if (claim == null) {
            // The cursor was past the end because a claim went away; the line
            // above has already wrapped it, so the next pass picks up again.
            return;
        }

        World world = plugin.getServer().getWorld(claim.worldId());
        if (world == null || !world.isChunkLoaded(claim.x() >> 4, claim.z() >> 4)) {
            return;
        }
        if (world.getBlockAt(claim.x(), claim.y(), claim.z()).getType().name().endsWith("BANNER")) {
            return;
        }
        Guild guild = byId.get(claim.guildId());
        plugin.getLogger().info("Claim at " + claim.describe() + " lost its banner; the claim is gone.");
        if (guild != null) {
            guild.claim(null);
            broadcast(guild, ChatColor.YELLOW + "[영지] " + claim.describe()
                    + " 의 깃발이 사라져 영지가 해제되었습니다.");
            // Same rule as breaking it by hand: a war objective that stops
            // existing settles the war rather than running out the clock.
            forfeitWarIfAny(guild, "깃발이 사라짐");
        }
        claims.remove(claim);
        save();
    }

    // -------------------------------------------------------------- treasury

    /**
     * Puts a member's own gold into the guild's.
     *
     * Any member may pay in; only the leader may take out or spend. A guild
     * whose every member could withdraw would not have a treasury, it would
     * have a shared wallet with the weakest link holding it.
     */
    public void deposit(Player player, int amount) {
        Guild guild = guildOf(player);
        if (guild == null) {
            player.sendMessage(ChatColor.RED + "[길드] 길드에 속해 있지 않습니다.");
            return;
        }
        if (amount <= 0) {
            player.sendMessage(ChatColor.RED + "[길드] 1 골드 이상 넣어 주세요.");
            return;
        }
        if (!plugin.economy().take(player, amount)) {
            player.sendMessage(ChatColor.RED + "[길드] 골드가 부족합니다. (보유 "
                    + plugin.economy().balance(player) + ")");
            return;
        }
        guild.gold(addSaturating(guild.gold(), amount));
        save();
        broadcast(guild, ChatColor.GREEN + "[길드] " + player.getName() + " 이(가) 금고에 "
                + plugin.economy().format(amount) + ChatColor.GREEN + " 을(를) 넣었습니다. "
                + ChatColor.GRAY + "(금고 " + guild.gold() + ")");
    }

    public void withdraw(Player player, int amount) {
        Guild guild = requireLeader(player, "금고에서 인출");
        if (guild == null || treasuryFrozen(player, guild, "꺼낼")) {
            return;
        }
        if (amount <= 0 || amount > guild.gold()) {
            player.sendMessage(ChatColor.RED + "[길드] 금고에 " + guild.gold() + " 골드가 있습니다.");
            return;
        }
        guild.gold(guild.gold() - amount);
        // refund, not give: guild gold was already earned once by whoever
        // deposited it, so taking it out must not count as earning it again.
        plugin.economy().refund(player, amount);
        save();
        broadcast(guild, ChatColor.YELLOW + "[길드] " + player.getName() + " 이(가) 금고에서 "
                + plugin.economy().format(amount) + ChatColor.YELLOW + " 을(를) 꺼냈습니다. "
                + ChatColor.GRAY + "(금고 " + guild.gold() + ")");
    }

    /**
     * True, with a message, when a war means the treasury cannot be drained.
     *
     * The prize is whatever is in the vault, so a treasury that can be emptied
     * after the declaration is not a prize - and the preparation window, which
     * exists so defenders can gather, is exactly when a leader would empty it.
     * Paying in is still allowed: reinforcing at the cost of a bigger prize is
     * a decision worth having.
     */
    private boolean treasuryFrozen(Player player, Guild guild, String what) {
        if (!atWar(guild.id())) {
            return false;
        }
        player.sendMessage(ChatColor.RED + "[길드] 전쟁 중에는 금고에서 골드를 " + what + " 수 없습니다.");
        player.sendMessage(ChatColor.GRAY + "  지금 금고에 있는 " + guild.gold()
                + " 골드가 이 전쟁에 걸려 있습니다. 넣는 것은 가능합니다.");
        return true;
    }

    /**
     * Converts treasury gold into territory.
     *
     * One-way, and that is the point. Gold sitting in the treasury is what a
     * war takes; gold spent on land is not. A guild choosing between hoarding
     * and expanding is choosing between a bigger prize for whoever beats them
     * and a bigger home they cannot be robbed of.
     */
    public void invest(Player player, int amount) {
        Guild guild = requireLeader(player, "투자");
        if (guild == null) {
            return;
        }
        if (treasuryFrozen(player, guild, "투자할")) {
            return;
        }
        int perBlock = plugin.rpgConfig().guildInvestPerBlock();
        if (perBlock <= 0) {
            player.sendMessage(ChatColor.RED + "[길드] 이 서버에서는 영지를 넓힐 수 없습니다.");
            return;
        }
        int before = radiusFor(guild);
        if (before >= plugin.rpgConfig().guildMaxRadius()) {
            player.sendMessage(ChatColor.YELLOW + "[길드] 이미 최대 반경(" + before + ")입니다.");
            return;
        }
        if (amount <= 0 || amount > guild.gold()) {
            player.sendMessage(ChatColor.RED + "[길드] 금고에 " + guild.gold() + " 골드가 있습니다. "
                    + ChatColor.GRAY + "(다음 1블록까지 " + nextRadiusCost(guild) + ")");
            return;
        }

        guild.gold(guild.gold() - amount);
        guild.invested(addSaturating(guild.invested(), amount));
        int after = radiusFor(guild);
        applyRadius(guild, after);
        save();

        broadcast(guild, ChatColor.GREEN + "[길드] " + player.getName() + " 이(가) "
                + plugin.economy().format(amount) + ChatColor.GREEN + " 을(를) 투자했습니다. "
                + ChatColor.GRAY + "(누적 " + guild.invested() + ")");
        if (after > before) {
            broadcast(guild, ChatColor.GREEN + "[영지] 영지 반경이 " + before + " -> " + after
                    + " 로 넓어졌습니다.");
        } else {
            player.sendMessage(ChatColor.GRAY + "  다음 1블록까지 " + nextRadiusCost(guild) + " 골드.");
        }
    }

    /**
     * Resizes a guild's claim in place.
     *
     * The claim has to leave and re-enter the chunk index, because the index
     * files it under the chunks it covers and a wider claim covers more of
     * them - changing the radius without reindexing would leave the new ring
     * unprotected and invisible to every lookup.
     */
    private void applyRadius(Guild guild, int radius) {
        GuildClaim claim = guild.claim();
        if (claim == null || claim.radius() == radius) {
            return;
        }
        claims.remove(claim);
        claim.radius(radius);
        claims.add(claim);
    }

    private static int addSaturating(int a, int b) {
        return (int) Math.min((long) a + b, Integer.MAX_VALUE);
    }

    // ------------------------------------------------------------------ war

    public List<GuildWar> liveWars() {
        long now = System.currentTimeMillis();
        List<GuildWar> live = new ArrayList<>();
        for (GuildWar war : wars) {
            if (war.phase(now) != GuildWar.Phase.OVER) {
                live.add(war);
            }
        }
        return live;
    }

    /** The war this guild is in, or null. A guild fights one war at a time. */
    public GuildWar warOf(UUID guildId) {
        long now = System.currentTimeMillis();
        for (GuildWar war : wars) {
            if (war.involves(guildId) && war.phase(now) != GuildWar.Phase.OVER) {
                return war;
            }
        }
        return null;
    }

    public boolean atWar(UUID guildId) {
        return warOf(guildId) != null;
    }

    /** The live war between these two, if they are fighting each other. */
    public GuildWar warBetween(UUID a, UUID b) {
        long now = System.currentTimeMillis();
        for (GuildWar war : wars) {
            if (war.isBetween(a, b) && war.phase(now) != GuildWar.Phase.OVER) {
                return war;
            }
        }
        return null;
    }

    /**
     * Declares war. Costs gold from the treasury, which the declarer does not
     * get back - a war that is free to start is a war that is always running.
     */
    public void declareWar(Player player, String targetName) {
        Guild attacker = requireLeader(player, "선전포고");
        if (attacker == null) {
            return;
        }
        Guild defender = byName(targetName);
        if (defender == null) {
            player.sendMessage(ChatColor.RED + "[전쟁] 그런 길드가 없습니다: " + targetName);
            return;
        }
        if (defender.id().equals(attacker.id())) {
            player.sendMessage(ChatColor.RED + "[전쟁] 자기 길드에는 선포할 수 없습니다.");
            return;
        }
        // Both flags have to be standing. A war is won by taking the enemy's
        // banner, so against a guild with no banner there is nothing to win -
        // and a guild with no banner of its own would be wagering nothing.
        if (!attacker.hasClaim()) {
            player.sendMessage(ChatColor.RED + "[전쟁] 우리 길드의 깃발이 서 있어야 선포할 수 있습니다.");
            return;
        }
        if (!defender.hasClaim()) {
            player.sendMessage(ChatColor.RED + "[전쟁] 상대 길드에 영지가 없어 빼앗을 것이 없습니다.");
            return;
        }
        if (atWar(attacker.id())) {
            player.sendMessage(ChatColor.RED + "[전쟁] 이미 전쟁 중입니다.");
            return;
        }
        if (atWar(defender.id())) {
            player.sendMessage(ChatColor.RED + "[전쟁] 상대는 이미 다른 전쟁 중입니다.");
            return;
        }
        long cooldown = cooldownLeft(attacker.id(), defender.id());
        if (cooldown > 0) {
            player.sendMessage(ChatColor.RED + "[전쟁] 최근에 맞붙었습니다. " + cooldown + "분 뒤에 가능합니다.");
            return;
        }
        int cost = plugin.rpgConfig().guildWarCost();
        if (cost > 0 && attacker.gold() < cost) {
            player.sendMessage(ChatColor.RED + "[전쟁] 선전포고에 금고의 " + cost
                    + " 골드가 필요합니다. (금고 " + attacker.gold() + ")");
            return;
        }
        if (cost > 0) {
            attacker.gold(attacker.gold() - cost);
        }

        long now = System.currentTimeMillis();
        long prep = plugin.rpgConfig().guildWarPrepMinutes() * 60_000L;
        long duration = plugin.rpgConfig().guildWarDurationMinutes() * 60_000L;
        GuildWar war = new GuildWar(attacker.id(), defender.id(), now, now + prep, now + prep + duration);
        wars.add(war);
        save();

        announceWar(ChatColor.DARK_RED + "[전쟁] " + ChatColor.WHITE + attacker.name()
                + ChatColor.DARK_RED + " 이(가) " + ChatColor.WHITE + defender.name()
                + ChatColor.DARK_RED + " 에 선전포고했습니다!");
        String when = prep > 0
                ? ChatColor.GRAY + "  " + plugin.rpgConfig().guildWarPrepMinutes() + "분 뒤 교전이 시작됩니다."
                : ChatColor.GRAY + "  교전이 즉시 시작됩니다.";
        announceWar(when + " 상대 깃발을 부수는 쪽이 이기고, 진 길드의 금고를 가져갑니다.");
        broadcast(defender, ChatColor.DARK_RED + "[전쟁] 방어 준비를 하세요. 우리 깃발이 목표입니다: "
                + ChatColor.WHITE + defender.claim().describe());
    }

    /**
     * A banner came down in a war. The guild that took it wins, and the loser's
     * treasury goes with the flag.
     */
    private void winWar(GuildWar war, Guild winner, Guild loser, String how) {
        if (!war.settle()) {
            return;
        }
        int share = plugin.rpgConfig().guildWarPrizePercent();
        int prize = (int) ((long) loser.gold() * share / 100L);
        if (prize > 0) {
            loser.gold(loser.gold() - prize);
            winner.gold(addSaturating(winner.gold(), prize));
        }
        startCooldown(war.attacker(), war.defender());
        save();

        announceWar(ChatColor.GOLD + "[전쟁] " + ChatColor.WHITE + winner.name()
                + ChatColor.GOLD + " 이(가) " + ChatColor.WHITE + loser.name()
                + ChatColor.GOLD + " 에게 승리했습니다! " + ChatColor.GRAY + "(" + how + ")");
        if (prize > 0) {
            announceWar(ChatColor.GRAY + "  전리품: " + plugin.economy().format(prize)
                    + ChatColor.GRAY + " 이(가) " + winner.name() + " 금고로 넘어갔습니다.");
        } else {
            announceWar(ChatColor.GRAY + "  진 길드의 금고가 비어 있어 가져갈 것은 없었습니다.");
        }
    }

    /**
     * Settles a war this guild is in as a loss, if it is in one.
     *
     * Every way a guild can stop being a target while a war is running goes
     * through here: knocking down its own flag, losing it to something no
     * listener saw, or disbanding outright. Without that, each of those is a
     * way to keep a treasury that was already on the table - and the cheapest
     * of them is simply to disband and found the guild again.
     */
    private void forfeitWarIfAny(Guild loser, String how) {
        GuildWar war = warOf(loser.id());
        if (war == null) {
            return;
        }
        Guild winner = byId.get(war.opponentOf(loser.id()));
        if (winner == null) {
            war.settle();
            return;
        }
        winWar(war, winner, loser, how);
    }

    /** Ends wars whose clock ran out with both flags still standing. */
    public void tickWars() {
        if (wars.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        // Iterated directly: settling a war does not touch the list, and the
        // one removal happens after. Copying here would allocate twenty times
        // a second for the whole length of every war.
        for (GuildWar war : wars) {
            if (war.over() || now < war.endsAtMs()) {
                continue;
            }
            if (!war.settle()) {
                continue;
            }
            Guild attacker = byId.get(war.attacker());
            Guild defender = byId.get(war.defender());
            startCooldown(war.attacker(), war.defender());
            if (attacker != null && defender != null) {
                announceWar(ChatColor.YELLOW + "[전쟁] " + attacker.name() + " 과(와) "
                        + defender.name() + " 의 전쟁이 시간이 다 되어 무승부로 끝났습니다. "
                        + ChatColor.GRAY + "(양쪽 깃발 모두 건재)");
            }
            save();
        }
        // Settled wars are kept only long enough to be reported, then dropped
        // so the list cannot grow for the life of the server.
        wars.removeIf(war -> war.over() && now - war.endsAtMs() > 600_000L);
    }

    private void endWarsInvolving(UUID guildId, String reason) {
        for (GuildWar war : wars) {
            if (war.involves(guildId) && war.settle()) {
                Guild other = byId.get(war.opponentOf(guildId));
                if (other != null) {
                    broadcast(other, ChatColor.YELLOW + "[전쟁] " + reason);
                }
            }
        }
    }

    private String pairKey(UUID a, UUID b) {
        return a.compareTo(b) <= 0 ? a + ":" + b : b + ":" + a;
    }

    private void startCooldown(UUID a, UUID b) {
        warCooldowns.put(pairKey(a, b), System.currentTimeMillis()
                + plugin.rpgConfig().guildWarCooldownMinutes() * 60_000L);
    }

    /** Cooldowns still running, for the storage layer. */
    Map<String, Long> activeCooldowns() {
        long now = System.currentTimeMillis();
        warCooldowns.entrySet().removeIf(e -> e.getValue() <= now);
        return warCooldowns;
    }

    void restoreCooldown(String key, long until) {
        if (until > System.currentTimeMillis()) {
            warCooldowns.put(key, until);
        }
    }

    /**
     * Re-derives every claim's radius from what its guild has invested.
     *
     * Called on /rpgcore reload so a changed base radius or block price takes
     * effect on land that is already claimed, rather than leaving existing
     * guilds on the numbers that were in the file when they planted.
     */
    public void refreshRadii() {
        for (Guild guild : byId.values()) {
            if (guild.hasClaim()) {
                applyRadius(guild, radiusFor(guild));
            }
        }
    }

    /** Minutes still to wait before these two may fight again; 0 when ready. */
    private long cooldownLeft(UUID a, UUID b) {
        Long until = warCooldowns.get(pairKey(a, b));
        if (until == null) {
            return 0L;
        }
        long left = until - System.currentTimeMillis();
        return left <= 0L ? 0L : (left + 59_999L) / 60_000L;
    }

    private void announceWar(String line) {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            player.sendMessage(line);
        }
    }

    // --------------------------------------------------------------- talking

    public void broadcast(Guild guild, String message) {
        for (UUID uuid : guild.ordered()) {
            Player member = plugin.getServer().getPlayer(uuid);
            if (member != null && member.isOnline()) {
                member.sendMessage(message);
            }
        }
    }

    public void chat(Player sender, String message) {
        Guild guild = guildOf(sender);
        if (guild == null) {
            sender.sendMessage(ChatColor.RED + "[길드] 길드에 속해 있지 않습니다.");
            return;
        }
        String prefix = ChatColor.translateAlternateColorCodes('&',
                plugin.rpgConfig().guildChatPrefix().replace("%guild%", guild.name()));
        broadcast(guild, prefix + ChatColor.WHITE + sender.getName() + ChatColor.GRAY + ": "
                + ChatColor.WHITE + message);
    }

    public List<String> roster(Guild guild) {
        List<String> lines = new ArrayList<>();
        for (UUID uuid : guild.ordered()) {
            Player member = plugin.getServer().getPlayer(uuid);
            boolean online = member != null && member.isOnline();
            String role = guild.isLeader(uuid) ? ChatColor.GOLD + "[장] " : ChatColor.GRAY + "    ";
            String state = online ? ChatColor.GREEN + "온라인" : ChatColor.DARK_GRAY + "오프라인";
            String level = "";
            if (online && plugin.players().cached(uuid) != null) {
                level = ChatColor.GRAY + " Lv." + plugin.players().cached(uuid).level();
            }
            lines.add(role + (online ? ChatColor.WHITE : ChatColor.GRAY) + guild.nameOf(uuid)
                    + level + ChatColor.GRAY + " - " + state);
        }
        return lines;
    }

    public List<String> memberNames(Guild guild) {
        List<String> names = new ArrayList<>();
        for (UUID uuid : guild.ordered()) {
            names.add(guild.nameOf(uuid));
        }
        return names;
    }

    private void purgeExpired(Map<UUID, Long> pending) {
        if (pending != null) {
            long now = System.currentTimeMillis();
            pending.entrySet().removeIf(e -> e.getValue() < now);
        }
    }
}
