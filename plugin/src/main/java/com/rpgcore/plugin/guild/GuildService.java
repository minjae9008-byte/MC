package com.rpgcore.plugin.guild;

import com.rpgcore.plugin.RpgCorePlugin;
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

    public GuildService(RpgCorePlugin plugin) {
        this.plugin = plugin;
        this.storage = new GuildStorage(plugin);
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
            for (GuildClaim claim : guild.claims()) {
                claims.add(claim);
            }
            parkVault(guild, loaded);
        }
        plugin.getLogger().info("Guilds loaded: " + byId.size() + " with "
                + claims.size() + " claim(s).");
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

    public void save() {
        storage.save(byId.values());
        for (Guild guild : byId.values()) {
            guild.clearVaultDirty();
        }
    }

    public int count() {
        return byId.size();
    }

    public int claimCount() {
        return claims.size();
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

        int returned = emptyVaultTo(guild, guild.leader(), "길드 해체");
        for (GuildClaim claim : guild.claims()) {
            claims.remove(claim);
            guild.removeClaim(claim);
            returnBanner(claim, guild.leader());
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
    public void handleVaultClosed(UUID guildId) {
        Guild guild = byId.get(guildId);
        if (guild != null) {
            guild.markVaultDirty();
            save();
        }
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

    /** The claim covering this location, or null. Hot path: chunk-indexed. */
    public GuildClaim claimAt(Location location) {
        World world = location.getWorld();
        return world == null ? null
                : claims.at(world, location.getBlockX(), location.getBlockZ());
    }

    public GuildClaim claimAt(World world, int x, int z) {
        return claims.at(world, x, z);
    }

    /**
     * True when this player may change blocks here: there is no claim, or they
     * belong to the guild that holds it. Ops are always allowed, so an
     * administrator is never locked out of their own server.
     */
    public boolean mayBuild(Player player, Location location) {
        GuildClaim claim = claimAt(location);
        if (claim == null || player.hasPermission("rpgcore.admin")) {
            return true;
        }
        Guild guild = byId.get(claim.guildId());
        return guild != null && guild.contains(player.getUniqueId());
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
        int max = plugin.rpgConfig().guildMaxClaims();
        if (guild.claimCount() >= max) {
            player.sendMessage(ChatColor.RED + "[영지] 길드가 가질 수 있는 영지는 " + max + "곳까지입니다.");
            return false;
        }
        World world = bannerBlock.getWorld();
        if (world == null) {
            return false;
        }

        int radius = plugin.rpgConfig().guildClaimRadius();
        GuildClaim proposed = new GuildClaim(guild.id(), world.getUID(),
                bannerBlock.getBlockX(), bannerBlock.getBlockY(), bannerBlock.getBlockZ(), radius);
        GuildClaim clash = firstOverlap(proposed);
        if (clash != null) {
            Guild other = byId.get(clash.guildId());
            player.sendMessage(ChatColor.RED + "[영지] 너무 가깝습니다. "
                    + (other == null ? "다른 영지" : "'" + other.name() + "' 영지")
                    + " 와(과) 거리를 두어야 합니다.");
            return false;
        }

        guild.addClaim(proposed);
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
     */
    private GuildClaim firstOverlap(GuildClaim proposed) {
        int gap = plugin.rpgConfig().guildClaimGap();
        for (GuildClaim existing : claims.allIn(proposed.worldId())) {
            double distance = existing.centreDistance(proposed);
            if (distance >= 0 && distance < existing.radius() + proposed.radius() + gap) {
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
            guild.removeClaim(claim);
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
        List<GuildClaim> all = new ArrayList<>();
        for (Guild guild : byId.values()) {
            all.addAll(guild.claims());
        }
        if (all.isEmpty()) {
            return;
        }
        validationCursor = (validationCursor + 1) % all.size();
        GuildClaim claim = all.get(validationCursor);

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
            guild.removeClaim(claim);
            broadcast(guild, ChatColor.YELLOW + "[영지] " + claim.describe()
                    + " 의 깃발이 사라져 영지가 해제되었습니다.");
        }
        claims.remove(claim);
        save();
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
