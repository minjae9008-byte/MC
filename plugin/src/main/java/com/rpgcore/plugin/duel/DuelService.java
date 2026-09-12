package com.rpgcore.plugin.duel;

import com.rpgcore.plugin.RpgCorePlugin;
import com.rpgcore.plugin.collection.CollectionService;
import com.rpgcore.plugin.progress.CounterType;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Consensual 1v1 duels with a wager.
 *
 * Two rules shape the whole design. Both sides opt in explicitly, so a duel
 * can never be started on someone who did not ask for one. And nobody dies:
 * the killing blow is cancelled and turned into a result, so the stake is the
 * only thing at risk - losing a duel never scatters a player's inventory on
 * the floor, which is what would make wagering anything worth having reckless.
 *
 * Stakes are escrowed on start. Gold is taken from the balance and items are
 * lifted out of the hand, so what has been bet cannot be spent, dropped or
 * traded away mid-fight, and a server crash mid-duel returns both stakes on
 * the way down rather than duplicating them.
 */
public final class DuelService {

    /** A pending invitation: what was offered, and until when. */
    private record Request(DuelStake stake, long expiresAtMs) {
    }

    private final RpgCorePlugin plugin;
    private final Map<UUID, DuelSession> sessions = new ConcurrentHashMap<>();
    /** invited player -> (challenger -> request). */
    private final Map<UUID, Map<UUID, Request>> requests = new ConcurrentHashMap<>();

    public DuelService(RpgCorePlugin plugin) {
        this.plugin = plugin;
    }

    public boolean enabled() {
        return plugin.rpgConfig().duelEnabled();
    }

    public DuelSession sessionOf(Player player) {
        return player == null ? null : sessions.get(player.getUniqueId());
    }

    public boolean inDuel(Player a, Player b) {
        DuelSession session = sessionOf(a);
        return session != null && !session.finished() && session.contains(b.getUniqueId());
    }

    public int count() {
        return sessions.size() / 2;
    }

    // ---------------------------------------------------------------- invite

    public void challenge(Player from, Player to, String wager) {
        if (from.equals(to)) {
            from.sendMessage(ChatColor.RED + "[대결] 자기 자신에게 신청할 수 없습니다.");
            return;
        }
        if (sessionOf(from) != null || sessionOf(to) != null) {
            from.sendMessage(ChatColor.RED + "[대결] 둘 중 한 명이 이미 대결 중입니다.");
            return;
        }
        if (!from.getWorld().getPVP()) {
            // Without this the challenge would be accepted and then simply do
            // nothing, because the server refuses player damage outright.
            from.sendMessage(ChatColor.RED + "[대결] 이 월드는 PvP 가 꺼져 있어 대결할 수 없습니다.");
            return;
        }
        if (!inRange(from, to)) {
            from.sendMessage(ChatColor.RED + "[대결] 상대가 너무 멀리 있습니다.");
            return;
        }

        DuelStake stake = parseStake(from, wager);
        if (stake == null) {
            return;
        }

        long expiry = System.currentTimeMillis() + plugin.rpgConfig().duelRequestSeconds() * 1000L;
        requests.computeIfAbsent(to.getUniqueId(), k -> new HashMap<>())
                .put(from.getUniqueId(), new Request(stake, expiry));

        from.sendMessage(ChatColor.GREEN + "[대결] " + to.getName() + " 에게 대결을 신청했습니다. "
                + ChatColor.GRAY + "(" + stake.describe() + ChatColor.GRAY + ")");
        to.sendMessage(ChatColor.GOLD + "[대결] " + ChatColor.WHITE + from.getName()
                + ChatColor.GOLD + " 님이 대결을 신청했습니다. " + ChatColor.GRAY + "걸린 것: " + stake.describe());
        to.sendMessage(ChatColor.YELLOW + "  /duel accept " + from.getName()
                + ChatColor.GRAY + " 또는 " + ChatColor.YELLOW + "/duel deny"
                + ChatColor.GRAY + " (" + plugin.rpgConfig().duelRequestSeconds() + "초 안에)");
        to.playSound(to.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0F, 1.0F);
    }

    /**
     * Reads the wager argument. Returns null after telling the player why it
     * was refused; {@link DuelStake#NONE} for a friendly match.
     */
    private DuelStake parseStake(Player player, String wager) {
        if (wager == null || wager.isBlank()) {
            return DuelStake.NONE;
        }
        if (wager.equalsIgnoreCase("hand") || wager.equals("손") || wager.equals("아이템")) {
            ItemStack held = player.getInventory().getItemInMainHand();
            if (held == null || held.getType().isAir()) {
                player.sendMessage(ChatColor.RED + "[대결] 손에 든 아이템이 없습니다.");
                return null;
            }
            return new DuelStake(0, held.clone());
        }

        int gold;
        try {
            gold = Integer.parseInt(wager);
        } catch (NumberFormatException e) {
            player.sendMessage(ChatColor.RED + "[대결] 걸 것을 숫자(골드)나 'hand'(손에 든 아이템)로 적어 주세요.");
            return null;
        }
        if (gold <= 0) {
            player.sendMessage(ChatColor.RED + "[대결] 1 골드 이상 걸어야 합니다.");
            return null;
        }
        int max = plugin.rpgConfig().duelMaxGold();
        if (max > 0 && gold > max) {
            player.sendMessage(ChatColor.RED + "[대결] 한 번에 걸 수 있는 금액은 " + max + " 골드까지입니다.");
            return null;
        }
        if (!plugin.economy().canAfford(player, gold)) {
            player.sendMessage(ChatColor.RED + "[대결] 골드가 부족합니다. (보유 "
                    + plugin.economy().balance(player) + ")");
            return null;
        }
        return new DuelStake(gold, null);
    }

    /** challengerName may be null when there is exactly one pending request. */
    public void accept(Player player, String challengerName) {
        Map<UUID, Request> pending = requests.get(player.getUniqueId());
        purgeExpired(pending);
        if (pending == null || pending.isEmpty()) {
            player.sendMessage(ChatColor.RED + "[대결] 받은 신청이 없습니다.");
            return;
        }

        UUID challengerId = resolve(player, pending, challengerName);
        if (challengerId == null) {
            return;
        }
        Request request = pending.remove(challengerId);
        Player challenger = plugin.getServer().getPlayer(challengerId);
        if (challenger == null || !challenger.isOnline()) {
            player.sendMessage(ChatColor.RED + "[대결] 상대가 접속 중이 아닙니다.");
            return;
        }
        if (sessionOf(player) != null || sessionOf(challenger) != null) {
            player.sendMessage(ChatColor.RED + "[대결] 둘 중 한 명이 이미 대결 중입니다.");
            return;
        }
        if (!player.getWorld().getPVP() || !challenger.getWorld().equals(player.getWorld())) {
            player.sendMessage(ChatColor.RED + "[대결] 지금은 대결할 수 없는 상태입니다.");
            return;
        }
        if (!inRange(challenger, player)) {
            player.sendMessage(ChatColor.RED + "[대결] 상대가 너무 멀리 있습니다.");
            return;
        }

        // The accepting side is asked to match what was offered, so both put
        // up the same thing and the winner's take is never a surprise.
        DuelStake mine = matchStake(player, request.stake());
        if (mine == null) {
            return;
        }
        if (!escrow(challenger, request.stake())) {
            player.sendMessage(ChatColor.RED + "[대결] 상대가 건 것을 더 이상 낼 수 없습니다.");
            challenger.sendMessage(ChatColor.RED + "[대결] 걸었던 것을 낼 수 없어 대결이 취소되었습니다.");
            return;
        }
        if (!escrow(player, mine)) {
            // Give the challenger theirs back rather than leaving it held.
            payOut(challenger, request.stake());
            player.sendMessage(ChatColor.RED + "[대결] 걸 것을 낼 수 없습니다.");
            return;
        }

        start(challenger, player, request.stake(), mine);
    }

    /** Builds the accepting side's matching stake, or null with a reason. */
    private DuelStake matchStake(Player player, DuelStake offered) {
        if (offered.isEmpty()) {
            return DuelStake.NONE;
        }
        if (offered.item() != null) {
            ItemStack held = player.getInventory().getItemInMainHand();
            if (held == null || held.getType().isAir()) {
                player.sendMessage(ChatColor.RED + "[대결] 상대는 "
                        + CollectionService.nameOf(offered.item()) + " 을(를) 걸었습니다. "
                        + "같은 아이템을 손에 들고 수락하세요.");
                return null;
            }
            if (held.getType() != offered.item().getType() || held.getAmount() < offered.item().getAmount()) {
                player.sendMessage(ChatColor.RED + "[대결] 상대와 같은 아이템을 같은 개수만큼 들고 있어야 합니다. ("
                        + CollectionService.nameOf(offered.item()) + " x" + offered.item().getAmount() + ")");
                return null;
            }
            ItemStack matched = held.clone();
            matched.setAmount(offered.item().getAmount());
            return new DuelStake(0, matched);
        }
        if (!plugin.economy().canAfford(player, offered.gold())) {
            player.sendMessage(ChatColor.RED + "[대결] " + offered.gold() + " 골드가 필요합니다. (보유 "
                    + plugin.economy().balance(player) + ")");
            return null;
        }
        return new DuelStake(offered.gold(), null);
    }

    private UUID resolve(Player player, Map<UUID, Request> pending, String challengerName) {
        if (challengerName == null) {
            if (pending.size() > 1) {
                player.sendMessage(ChatColor.RED + "[대결] 신청이 여러 개입니다. /duel accept <플레이어> 로 지정하세요.");
                return null;
            }
            return pending.keySet().iterator().next();
        }
        for (UUID uuid : pending.keySet()) {
            Player candidate = plugin.getServer().getPlayer(uuid);
            if (candidate != null && candidate.getName().equalsIgnoreCase(challengerName)) {
                return uuid;
            }
        }
        player.sendMessage(ChatColor.RED + "[대결] " + challengerName + " 에게 받은 신청이 없습니다.");
        return null;
    }

    public void deny(Player player) {
        Map<UUID, Request> pending = requests.remove(player.getUniqueId());
        if (pending == null || pending.isEmpty()) {
            player.sendMessage(ChatColor.RED + "[대결] 받은 신청이 없습니다.");
            return;
        }
        player.sendMessage(ChatColor.YELLOW + "[대결] 신청을 거절했습니다.");
        for (UUID uuid : pending.keySet()) {
            Player challenger = plugin.getServer().getPlayer(uuid);
            if (challenger != null && challenger.isOnline()) {
                challenger.sendMessage(ChatColor.YELLOW + "[대결] " + player.getName() + " 님이 거절했습니다.");
            }
        }
    }

    // ----------------------------------------------------------------- fight

    private void start(Player a, Player b, DuelStake stakeA, DuelStake stakeB) {
        int countdown = plugin.rpgConfig().duelCountdownSeconds();
        DuelSession session = new DuelSession(a.getUniqueId(), b.getUniqueId(), stakeA, stakeB, countdown > 0);
        sessions.put(a.getUniqueId(), session);
        sessions.put(b.getUniqueId(), session);

        for (Player player : List.of(a, b)) {
            player.sendMessage("");
            player.sendMessage(ChatColor.RED + "  ⚔ " + ChatColor.WHITE + a.getName()
                    + ChatColor.GRAY + " vs " + ChatColor.WHITE + b.getName());
            player.sendMessage(ChatColor.GRAY + "    걸린 것: " + stakeA.describe()
                    + ChatColor.GRAY + " (양쪽 동일)");
            player.sendMessage(ChatColor.GRAY + "    쓰러뜨리면 승리합니다. 죽지는 않으니 아이템은 떨어지지 않습니다.");
            player.sendMessage(ChatColor.GRAY + "    포기하려면 " + ChatColor.YELLOW + "/duel forfeit");
            player.sendMessage("");
            // A duel decided by who happened to be at three hearts is not a
            // duel, so both start whole when the server asks for it.
            if (plugin.rpgConfig().duelHealBeforeStart()) {
                restore(player);
            }
        }

        if (countdown <= 0) {
            begin(session, a, b);
            return;
        }
        countdown(session, countdown);
    }

    /**
     * Counts both players in. The task holds no player references - it looks
     * them up each tick - so a duel whose players log off mid-countdown is
     * settled by the quit handler rather than by a stale reference here.
     */
    private void countdown(DuelSession session, int seconds) {
        new org.bukkit.scheduler.BukkitRunnable() {
            private int left = seconds;

            @Override
            public void run() {
                Player first = plugin.getServer().getPlayer(session.first());
                Player second = plugin.getServer().getPlayer(session.second());
                if (session.finished() || first == null || second == null) {
                    cancel();
                    return;
                }
                if (left <= 0) {
                    cancel();
                    begin(session, first, second);
                    return;
                }
                for (Player player : List.of(first, second)) {
                    player.sendActionBar(net.kyori.adventure.text.Component
                            .text("⚔ " + left + "..."));
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1.0F, 1.0F);
                }
                left--;
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    private void begin(DuelSession session, Player a, Player b) {
        session.begin();
        for (Player player : List.of(a, b)) {
            Player other = player.equals(a) ? b : a;
            player.sendActionBar(net.kyori.adventure.text.Component.text("⚔ " + other.getName()));
            player.playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.4F, 1.6F);
        }
    }

    /** The killing blow landed: no death, just a result. */
    public void finish(DuelSession session, Player winner, Player loser, String reason) {
        if (session.finished()) {
            return;
        }
        session.finish();
        sessions.remove(session.first());
        sessions.remove(session.second());

        // Both stakes go to the winner; the loser is put back on their feet so
        // the cancelled killing blow cannot leave them at half a heart.
        payOut(winner, session.stakeOf(winner.getUniqueId()));
        payOut(winner, session.stakeOf(loser.getUniqueId()));
        restore(winner);
        restore(loser);

        DuelStake pot = session.stakeOf(loser.getUniqueId());
        winner.sendMessage(ChatColor.GREEN + "[대결] 승리! " + ChatColor.WHITE + loser.getName()
                + ChatColor.GREEN + " 을(를) 이겼습니다."
                + (pot.isEmpty() ? "" : ChatColor.GRAY + " 획득: " + pot.describe()));
        loser.sendMessage(ChatColor.RED + "[대결] 패배… " + ChatColor.WHITE + winner.getName()
                + ChatColor.RED + " 에게 졌습니다."
                + (pot.isEmpty() ? "" : ChatColor.GRAY + " 잃음: " + pot.describe()));
        winner.playSound(winner.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0F, 1.4F);
        loser.playSound(loser.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0F, 1.0F);

        plugin.achievements().bump(winner, CounterType.DUELS_WON, 1);

        if (plugin.rpgConfig().duelAnnounce()) {
            String line = ChatColor.RED + "[대결] " + ChatColor.WHITE + winner.getName()
                    + ChatColor.GRAY + " 님이 " + ChatColor.WHITE + loser.getName()
                    + ChatColor.GRAY + " 님을 이겼습니다. " + reason;
            for (Player other : plugin.getServer().getOnlinePlayers()) {
                if (!other.equals(winner) && !other.equals(loser)) {
                    other.sendMessage(line);
                }
            }
        }
    }

    /** Both sides get their own stake back; nobody wins. */
    public void draw(DuelSession session, String reason) {
        if (session.finished()) {
            return;
        }
        session.finish();
        sessions.remove(session.first());
        sessions.remove(session.second());

        for (UUID uuid : List.of(session.first(), session.second())) {
            Player player = plugin.getServer().getPlayer(uuid);
            if (player != null && player.isOnline()) {
                payOut(player, session.stakeOf(uuid));
                restore(player);
                player.sendMessage(ChatColor.YELLOW + "[대결] " + reason + " 건 것은 그대로 돌려받았습니다.");
            } else {
                // Offline: the stake is held until they are back, rather than
                // silently dropped. Gold goes straight to the mirror; an item
                // waits in the pending list.
                holdForOffline(uuid, session.stakeOf(uuid));
            }
        }
    }

    public void forfeit(Player player) {
        DuelSession session = sessionOf(player);
        if (session == null) {
            player.sendMessage(ChatColor.RED + "[대결] 진행 중인 대결이 없습니다.");
            return;
        }
        Player opponent = plugin.getServer().getPlayer(session.opponentOf(player.getUniqueId()));
        if (opponent == null || !opponent.isOnline()) {
            draw(session, "상대가 접속을 종료했습니다.");
            return;
        }
        finish(session, opponent, player, ChatColor.GRAY + "(기권)");
    }

    /** Leaving mid-duel is a forfeit, so quitting is never a way to keep a stake. */
    public void handleQuit(Player player) {
        requests.remove(player.getUniqueId());
        DuelSession session = sessionOf(player);
        if (session == null) {
            return;
        }
        Player opponent = plugin.getServer().getPlayer(session.opponentOf(player.getUniqueId()));
        if (opponent != null && opponent.isOnline()) {
            finish(session, opponent, player, ChatColor.GRAY + "(접속 종료)");
        } else {
            draw(session, "양쪽 모두 접속이 끊겼습니다.");
        }
    }

    /** Called from the tick pump: ends duels nobody is finishing. */
    public void tick() {
        int limit = plugin.rpgConfig().duelMaxSeconds();
        if (limit <= 0 || sessions.isEmpty()) {
            return;
        }
        for (DuelSession session : new ArrayList<>(sessions.values())) {
            if (!session.finished() && !session.pending() && session.ageSeconds() >= limit) {
                draw(session, "제한 시간이 지나 무승부입니다.");
            }
        }
    }

    /** Unwinds every live duel, for shutdown and /rpgcore reload. */
    public void endAll(String reason) {
        for (DuelSession session : new ArrayList<>(sessions.values())) {
            draw(session, reason);
        }
        sessions.clear();
        requests.clear();
    }

    // ----------------------------------------------------------------- stake

    private boolean escrow(Player player, DuelStake stake) {
        if (stake.isEmpty()) {
            return true;
        }
        if (stake.item() != null) {
            ItemStack held = player.getInventory().getItemInMainHand();
            if (held == null || held.getType() != stake.item().getType()
                    || held.getAmount() < stake.item().getAmount()) {
                return false;
            }
            held.setAmount(held.getAmount() - stake.item().getAmount());
            player.getInventory().setItemInMainHand(held.getAmount() <= 0 ? null : held);
            return true;
        }
        return plugin.economy().take(player, stake.gold());
    }

    private void payOut(Player player, DuelStake stake) {
        if (stake.isEmpty()) {
            return;
        }
        if (stake.item() != null) {
            // A full inventory drops it at their feet rather than eating it.
            for (ItemStack leftover : player.getInventory().addItem(stake.item().clone()).values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            }
            return;
        }
        plugin.economy().refund(player, stake.gold());
    }

    /**
     * A stake belonging to someone who has already left. Gold goes straight
     * onto the scoreboard mirror, which works for an offline player, so a
     * refund is never lost to bad timing. An item has nowhere to go until they
     * are back, so it is logged by name for an operator to hand over.
     */
    private void holdForOffline(UUID uuid, DuelStake stake) {
        if (stake.isEmpty()) {
            return;
        }
        if (stake.item() == null) {
            plugin.players().grantOfflineGold(uuid, stake.gold());
            return;
        }
        plugin.getLogger().warning("A duel ended while " + uuid + " was offline; "
                + ChatColor.stripColor(stake.describe()) + " could not be returned automatically.");
    }

    /** Back on their feet: the cancelled killing blow must not leave a mark. */
    private void restore(Player player) {
        // A duel that ended because they died to something else is already
        // past healing; setting health on a corpse only confuses the client.
        if (player.isDead()) {
            return;
        }
        player.setFireTicks(0);
        player.setHealth(player.getAttribute(Attribute.MAX_HEALTH) == null
                ? 20.0D : player.getAttribute(Attribute.MAX_HEALTH).getValue());
    }

    private boolean inRange(Player a, Player b) {
        if (!a.getWorld().equals(b.getWorld())) {
            return false;
        }
        double max = plugin.rpgConfig().duelMaxDistance();
        return max <= 0 || a.getLocation().distanceSquared(b.getLocation()) <= max * max;
    }

    private void purgeExpired(Map<UUID, Request> pending) {
        if (pending != null) {
            long now = System.currentTimeMillis();
            pending.entrySet().removeIf(e -> e.getValue().expiresAtMs() < now);
        }
    }
}
