package com.rpgcore.plugin.party;

import com.rpgcore.plugin.RpgCorePlugin;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Party membership, names, invites and the XP share list.
 *
 * Every mutation goes through here so the two indexes - party by member and
 * pending invites - cannot drift apart, and so every change is written back to
 * parties.yml from one place. Parties survive a restart; the invites, being
 * short-lived by design, do not.
 */
public final class PartyService {

    private final RpgCorePlugin plugin;
    private final PartyStorage storage;
    private final Map<UUID, Party> byId = new LinkedHashMap<>();
    private final Map<UUID, Party> byMember = new ConcurrentHashMap<>();
    /** invited player -> (inviter -> expiry millis). */
    private final Map<UUID, Map<UUID, Long>> invites = new ConcurrentHashMap<>();

    public PartyService(RpgCorePlugin plugin) {
        this.plugin = plugin;
        this.storage = new PartyStorage(plugin);
    }

    public void load() {
        byId.clear();
        byMember.clear();
        for (Party party : storage.load()) {
            byId.put(party.id(), party);
            for (UUID member : party.members()) {
                byMember.put(member, party);
            }
        }
        plugin.getLogger().info("Parties loaded: " + byId.size() + ".");
    }

    public void save() {
        storage.save(byId.values());
    }

    public int count() {
        return byId.size();
    }

    public boolean enabled() {
        return plugin.rpgConfig().partyEnabled();
    }

    public Party partyOf(Player player) {
        return player == null ? null : byMember.get(player.getUniqueId());
    }

    public boolean sameParty(Player a, Player b) {
        Party party = partyOf(a);
        return party != null && party.contains(b.getUniqueId());
    }

    /**
     * Who a player's earned XP is split between: the earner plus party members
     * who are <em>online</em>, in the same world and within range. Offline
     * members never take a share - a party that persists across restarts would
     * otherwise quietly tax everyone for members who are not playing.
     *
     * A player with no party is a list of one, so callers need no special case.
     */
    public List<Player> shareTargets(Player earner) {
        List<Player> targets = new ArrayList<>();
        targets.add(earner);
        Party party = partyOf(earner);
        if (party == null || !enabled()) {
            return targets;
        }
        double range = plugin.rpgConfig().partyXpShareRange();
        double rangeSquared = range * range;
        for (UUID uuid : party.members()) {
            if (uuid.equals(earner.getUniqueId())) {
                continue;
            }
            Player member = plugin.getServer().getPlayer(uuid);
            if (member == null || !member.isOnline() || !member.getWorld().equals(earner.getWorld())) {
                continue;
            }
            if (range > 0 && member.getLocation().distanceSquared(earner.getLocation()) > rangeSquared) {
                continue;
            }
            targets.add(member);
        }
        return targets;
    }

    public Party create(Player leader, String requestedName) {
        if (partyOf(leader) != null) {
            leader.sendMessage(ChatColor.RED + "[파티] 이미 파티에 속해 있습니다.");
            return null;
        }
        String name = sanitiseName(leader, requestedName, leader.getName() + "의 파티");
        if (name == null) {
            return null;
        }

        Party party = new Party(UUID.randomUUID(), name, leader.getUniqueId(), leader.getName());
        byId.put(party.id(), party);
        byMember.put(leader.getUniqueId(), party);
        save();
        leader.sendMessage(ChatColor.GREEN + "[파티] '" + ChatColor.WHITE + name + ChatColor.GREEN
                + "' 파티를 만들었습니다. /party invite <플레이어> 로 초대하세요.");
        return party;
    }

    public void rename(Player leader, String requestedName) {
        Party party = partyOf(leader);
        if (party == null) {
            leader.sendMessage(ChatColor.RED + "[파티] 파티에 속해 있지 않습니다.");
            return;
        }
        if (!party.isLeader(leader.getUniqueId())) {
            leader.sendMessage(ChatColor.RED + "[파티] 파티장만 이름을 바꿀 수 있습니다.");
            return;
        }
        String name = sanitiseName(leader, requestedName, null);
        if (name == null) {
            return;
        }
        party.name(name);
        save();
        broadcast(party, ChatColor.GREEN + "[파티] 파티 이름이 '" + ChatColor.WHITE + name
                + ChatColor.GREEN + "' 으로 바뀌었습니다.");
    }

    /**
     * Trims a requested name to something usable, or returns null after
     * telling the player why it was refused. Colour codes are stripped so a
     * party cannot disguise itself as another one in chat.
     */
    private String sanitiseName(Player player, String requested, String fallback) {
        if (requested == null || requested.isBlank()) {
            if (fallback != null) {
                return fallback;
            }
            player.sendMessage(ChatColor.RED + "[파티] 이름을 입력하세요.");
            return null;
        }
        String name = ChatColor.stripColor(requested.replace('§', '&')).trim();
        int max = plugin.rpgConfig().partyNameMaxLength();
        if (name.isEmpty()) {
            player.sendMessage(ChatColor.RED + "[파티] 쓸 수 없는 이름입니다.");
            return null;
        }
        if (name.length() > max) {
            player.sendMessage(ChatColor.RED + "[파티] 이름은 " + max + "자까지입니다.");
            return null;
        }
        for (Party other : byId.values()) {
            if (!other.contains(player.getUniqueId()) && other.name().equalsIgnoreCase(name)) {
                player.sendMessage(ChatColor.RED + "[파티] 이미 쓰이는 이름입니다: " + name);
                return null;
            }
        }
        return name;
    }

    public void invite(Player inviter, Player target) {
        if (inviter.equals(target)) {
            inviter.sendMessage(ChatColor.RED + "[파티] 자기 자신은 초대할 수 없습니다.");
            return;
        }
        Party party = partyOf(inviter);
        if (party == null) {
            party = create(inviter, null);
            if (party == null) {
                return;
            }
        }
        if (!party.isLeader(inviter.getUniqueId())) {
            inviter.sendMessage(ChatColor.RED + "[파티] 파티장만 초대할 수 있습니다.");
            return;
        }
        if (party.contains(target.getUniqueId())) {
            inviter.sendMessage(ChatColor.RED + "[파티] 이미 파티원입니다.");
            return;
        }
        int max = plugin.rpgConfig().partyMaxSize();
        if (party.size() >= max) {
            inviter.sendMessage(ChatColor.RED + "[파티] 파티 정원(" + max + "명)이 찼습니다.");
            return;
        }

        long expiry = System.currentTimeMillis() + plugin.rpgConfig().partyInviteSeconds() * 1000L;
        invites.computeIfAbsent(target.getUniqueId(), k -> new HashMap<>())
                .put(inviter.getUniqueId(), expiry);

        inviter.sendMessage(ChatColor.GREEN + "[파티] " + target.getName() + " 을(를) 초대했습니다.");
        target.sendMessage(ChatColor.GOLD + "[파티] " + inviter.getName() + " 이(가) '"
                + ChatColor.WHITE + party.name() + ChatColor.GOLD + "' 파티에 초대했습니다. "
                + ChatColor.YELLOW + "/party accept " + inviter.getName()
                + ChatColor.GRAY + " (" + plugin.rpgConfig().partyInviteSeconds() + "초 안에)");
    }

    /** inviterName may be null when there is exactly one pending invite. */
    public void accept(Player player, String inviterName) {
        Map<UUID, Long> pending = invites.get(player.getUniqueId());
        purgeExpired(pending);
        if (pending == null || pending.isEmpty()) {
            player.sendMessage(ChatColor.RED + "[파티] 받은 초대가 없습니다.");
            return;
        }
        if (partyOf(player) != null) {
            player.sendMessage(ChatColor.RED + "[파티] 이미 파티에 속해 있습니다. 먼저 /party leave 하세요.");
            return;
        }

        UUID inviter = resolveInviter(player, pending, inviterName);
        if (inviter == null) {
            return;
        }
        Party party = byMember.get(inviter);
        if (party == null) {
            player.sendMessage(ChatColor.RED + "[파티] 그 파티는 더 이상 없습니다.");
            pending.remove(inviter);
            return;
        }
        if (party.size() >= plugin.rpgConfig().partyMaxSize()) {
            player.sendMessage(ChatColor.RED + "[파티] 파티 정원이 찼습니다.");
            return;
        }

        pending.remove(inviter);
        party.add(player.getUniqueId(), player.getName());
        byMember.put(player.getUniqueId(), party);
        save();
        broadcast(party, ChatColor.GREEN + "[파티] " + player.getName() + " 이(가) 파티에 들어왔습니다. ("
                + party.size() + "/" + plugin.rpgConfig().partyMaxSize() + ")");
    }

    private UUID resolveInviter(Player player, Map<UUID, Long> pending, String inviterName) {
        if (inviterName == null) {
            if (pending.size() > 1) {
                player.sendMessage(ChatColor.RED + "[파티] 초대가 여러 개입니다. /party accept <플레이어> 로 지정하세요.");
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
        player.sendMessage(ChatColor.RED + "[파티] " + inviterName + " 에게 받은 초대가 없습니다.");
        return null;
    }

    public void deny(Player player) {
        Map<UUID, Long> pending = invites.remove(player.getUniqueId());
        if (pending == null || pending.isEmpty()) {
            player.sendMessage(ChatColor.RED + "[파티] 받은 초대가 없습니다.");
            return;
        }
        player.sendMessage(ChatColor.YELLOW + "[파티] 초대를 거절했습니다.");
    }

    public void leave(Player player) {
        Party party = partyOf(player);
        if (party == null) {
            player.sendMessage(ChatColor.RED + "[파티] 파티에 속해 있지 않습니다.");
            return;
        }
        // A leader leaving hands the party over rather than taking everyone
        // else down with them; /party disband is there for that on purpose.
        if (party.isLeader(player.getUniqueId()) && party.size() == 1) {
            disband(player);
            return;
        }
        removeMember(party, player.getUniqueId());
        player.sendMessage(ChatColor.YELLOW + "[파티] 파티에서 나왔습니다.");
        broadcast(party, ChatColor.YELLOW + "[파티] " + player.getName() + " 이(가) 파티를 떠났습니다.");
    }

    public void kick(Player leader, String targetName) {
        Party party = partyOf(leader);
        if (party == null || !party.isLeader(leader.getUniqueId())) {
            leader.sendMessage(ChatColor.RED + "[파티] 파티장만 추방할 수 있습니다.");
            return;
        }
        // Matched on the stored name so an offline member can be removed too.
        for (UUID uuid : party.members()) {
            if (!targetName.equalsIgnoreCase(party.nameOf(uuid))) {
                continue;
            }
            if (party.isLeader(uuid)) {
                leader.sendMessage(ChatColor.RED + "[파티] 자기 자신은 추방할 수 없습니다.");
                return;
            }
            removeMember(party, uuid);
            Player member = plugin.getServer().getPlayer(uuid);
            if (member != null && member.isOnline()) {
                member.sendMessage(ChatColor.RED + "[파티] 파티에서 추방되었습니다.");
            }
            broadcast(party, ChatColor.YELLOW + "[파티] " + targetName + " 이(가) 추방되었습니다.");
            return;
        }
        leader.sendMessage(ChatColor.RED + "[파티] 파티에 그런 플레이어가 없습니다: " + targetName);
    }

    public void disband(Player leader) {
        Party party = partyOf(leader);
        if (party == null) {
            leader.sendMessage(ChatColor.RED + "[파티] 파티에 속해 있지 않습니다.");
            return;
        }
        if (!party.isLeader(leader.getUniqueId())) {
            leader.sendMessage(ChatColor.RED + "[파티] 파티장만 해체할 수 있습니다.");
            return;
        }
        broadcast(party, ChatColor.YELLOW + "[파티] '" + ChatColor.WHITE + party.name()
                + ChatColor.YELLOW + "' 파티가 해체되었습니다.");
        for (UUID uuid : party.members()) {
            byMember.remove(uuid);
        }
        byId.remove(party.id());
        save();
    }

    /** Keeps the stored name current, in case the player renamed. */
    public void handleJoin(Player player) {
        Party party = partyOf(player);
        if (party == null) {
            return;
        }
        if (!player.getName().equals(party.nameOf(player.getUniqueId()))) {
            party.refreshName(player.getUniqueId(), player.getName());
            save();
        }
        player.sendMessage(ChatColor.GRAY + "[파티] '" + ChatColor.WHITE + party.name()
                + ChatColor.GRAY + "' 파티에 속해 있습니다. (" + party.size() + "명)");
    }

    public void handleQuit(Player player) {
        invites.remove(player.getUniqueId());
        Party party = partyOf(player);
        if (party != null) {
            broadcast(party, ChatColor.GRAY + "[파티] " + player.getName() + " 이(가) 접속을 종료했습니다.");
        }
    }

    public void broadcast(Party party, String message) {
        for (UUID uuid : party.ordered()) {
            Player member = plugin.getServer().getPlayer(uuid);
            if (member != null && member.isOnline()) {
                member.sendMessage(message);
            }
        }
    }

    public void chat(Player sender, String message) {
        Party party = partyOf(sender);
        if (party == null) {
            sender.sendMessage(ChatColor.RED + "[파티] 파티에 속해 있지 않습니다.");
            return;
        }
        String prefix = ChatColor.translateAlternateColorCodes('&',
                plugin.rpgConfig().partyChatPrefix().replace("%party%", party.name()));
        broadcast(party, prefix + ChatColor.WHITE + sender.getName() + ChatColor.GRAY + ": "
                + ChatColor.WHITE + message);
    }

    public List<String> roster(Party party) {
        List<String> lines = new ArrayList<>();
        for (UUID uuid : party.ordered()) {
            Player member = plugin.getServer().getPlayer(uuid);
            boolean online = member != null && member.isOnline();
            String role = party.isLeader(uuid) ? ChatColor.GOLD + "[장] " : ChatColor.GRAY + "    ";
            String state = online ? ChatColor.GREEN + "온라인" : ChatColor.DARK_GRAY + "오프라인";
            String level = "";
            if (online && plugin.players().cached(uuid) != null) {
                level = ChatColor.GRAY + " Lv." + plugin.players().cached(uuid).level();
            }
            lines.add(role + (online ? ChatColor.WHITE : ChatColor.GRAY) + party.nameOf(uuid)
                    + level + ChatColor.GRAY + " - " + state);
        }
        return lines;
    }

    /** Names of every party member, for tab completion of /party kick. */
    public List<String> memberNames(Party party) {
        List<String> names = new ArrayList<>();
        for (UUID uuid : party.ordered()) {
            names.add(party.nameOf(uuid));
        }
        return names;
    }

    private void removeMember(Party party, UUID uuid) {
        boolean wasLeader = party.isLeader(uuid);
        party.remove(uuid);
        byMember.remove(uuid);
        if (party.size() == 0) {
            byId.remove(party.id());
        } else if (wasLeader) {
            UUID heir = party.ordered().iterator().next();
            party.leader(heir);
            broadcast(party, ChatColor.GOLD + "[파티] " + party.nameOf(heir) + " 이(가) 새 파티장이 되었습니다.");
        }
        save();
    }

    private void purgeExpired(Map<UUID, Long> pending) {
        if (pending != null) {
            long now = System.currentTimeMillis();
            pending.entrySet().removeIf(e -> e.getValue() < now);
        }
    }
}
