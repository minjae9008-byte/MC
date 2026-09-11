package com.rpgcore.plugin.trade;

import com.rpgcore.plugin.RpgCorePlugin;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Trade requests and the live sessions they turn into.
 *
 * A player is in at most one trade, and both of its participants map to the
 * same session object, so any exit path - closing the window, quitting, dying,
 * /trade cancel - reaches the one session that owns the items.
 */
public final class TradeService {

    private final RpgCorePlugin plugin;
    private final Map<UUID, TradeSession> sessions = new ConcurrentHashMap<>();
    /** target -> (requester -> expiry millis). */
    private final Map<UUID, Map<UUID, Long>> requests = new ConcurrentHashMap<>();

    public TradeService(RpgCorePlugin plugin) {
        this.plugin = plugin;
    }

    public boolean enabled() {
        return plugin.rpgConfig().tradeEnabled();
    }

    public TradeSession sessionOf(Player player) {
        return sessions.get(player.getUniqueId());
    }

    public void request(Player from, Player to) {
        if (from.equals(to)) {
            from.sendMessage(ChatColor.RED + "[거래] 자기 자신과는 거래할 수 없습니다.");
            return;
        }
        if (sessionOf(from) != null || sessionOf(to) != null) {
            from.sendMessage(ChatColor.RED + "[거래] 이미 거래 중입니다.");
            return;
        }
        if (!inRange(from, to)) {
            return;
        }

        long expiry = System.currentTimeMillis() + plugin.rpgConfig().tradeRequestSeconds() * 1000L;
        requests.computeIfAbsent(to.getUniqueId(), k -> new HashMap<>()).put(from.getUniqueId(), expiry);

        from.sendMessage(ChatColor.GREEN + "[거래] " + to.getName() + " 에게 거래를 요청했습니다.");
        to.sendMessage(ChatColor.GOLD + "[거래] " + from.getName() + " 이(가) 거래를 요청했습니다. "
                + ChatColor.YELLOW + "/trade accept " + from.getName()
                + ChatColor.GRAY + " (" + plugin.rpgConfig().tradeRequestSeconds() + "초 안에)");
    }

    /** requesterName may be null when there is exactly one pending request. */
    public void accept(Player player, String requesterName) {
        Map<UUID, Long> pending = requests.get(player.getUniqueId());
        purgeExpired(pending);
        if (pending == null || pending.isEmpty()) {
            player.sendMessage(ChatColor.RED + "[거래] 받은 거래 요청이 없습니다.");
            return;
        }
        if (sessionOf(player) != null) {
            player.sendMessage(ChatColor.RED + "[거래] 이미 거래 중입니다.");
            return;
        }

        UUID requesterId = resolveRequester(player, pending, requesterName);
        if (requesterId == null) {
            return;
        }
        Player requester = plugin.getServer().getPlayer(requesterId);
        if (requester == null || !requester.isOnline()) {
            player.sendMessage(ChatColor.RED + "[거래] 상대가 접속 중이 아닙니다.");
            pending.remove(requesterId);
            return;
        }
        if (sessionOf(requester) != null) {
            player.sendMessage(ChatColor.RED + "[거래] 상대가 이미 다른 거래 중입니다.");
            return;
        }
        if (!inRange(player, requester)) {
            return;
        }

        pending.remove(requesterId);
        TradeSession session = new TradeSession(plugin, requester, player);
        sessions.put(requester.getUniqueId(), session);
        sessions.put(player.getUniqueId(), session);
        session.open();
    }

    private UUID resolveRequester(Player player, Map<UUID, Long> pending, String requesterName) {
        if (requesterName == null) {
            if (pending.size() > 1) {
                player.sendMessage(ChatColor.RED + "[거래] 요청이 여러 개입니다. /trade accept <플레이어> 로 지정하세요.");
                return null;
            }
            return pending.keySet().iterator().next();
        }
        for (UUID uuid : pending.keySet()) {
            Player candidate = plugin.getServer().getPlayer(uuid);
            if (candidate != null && candidate.getName().equalsIgnoreCase(requesterName)) {
                return uuid;
            }
        }
        player.sendMessage(ChatColor.RED + "[거래] " + requesterName + " 에게 받은 요청이 없습니다.");
        return null;
    }

    public void deny(Player player) {
        Map<UUID, Long> pending = requests.remove(player.getUniqueId());
        if (pending == null || pending.isEmpty()) {
            player.sendMessage(ChatColor.RED + "[거래] 받은 거래 요청이 없습니다.");
            return;
        }
        player.sendMessage(ChatColor.YELLOW + "[거래] 거래 요청을 거절했습니다.");
    }

    /** Ends whatever trade a player is in, returning both sides' items. */
    public void cancel(Player player, String reason) {
        TradeSession session = sessionOf(player);
        if (session == null) {
            player.sendMessage(ChatColor.RED + "[거래] 진행 중인 거래가 없습니다.");
            return;
        }
        session.cancel(reason);
    }

    /** Same as cancel, but silent when the player is not trading. */
    public void endIfTrading(Player player, String reason) {
        TradeSession session = sessionOf(player);
        if (session != null) {
            session.cancel(reason);
        }
        requests.remove(player.getUniqueId());
    }

    void end(TradeSession session) {
        sessions.remove(session.left().getUniqueId(), session);
        sessions.remove(session.right().getUniqueId(), session);
    }

    private boolean inRange(Player a, Player b) {
        if (!a.getWorld().equals(b.getWorld())) {
            a.sendMessage(ChatColor.RED + "[거래] 상대가 다른 월드에 있습니다.");
            return false;
        }
        double max = plugin.rpgConfig().tradeMaxDistance();
        if (max > 0 && a.getLocation().distanceSquared(b.getLocation()) > max * max) {
            a.sendMessage(ChatColor.RED + "[거래] 상대가 너무 멀리 있습니다. (" + (int) max + "블록 이내)");
            return false;
        }
        return true;
    }

    private void purgeExpired(Map<UUID, Long> pending) {
        if (pending != null) {
            long now = System.currentTimeMillis();
            pending.entrySet().removeIf(e -> e.getValue() < now);
        }
    }
}
