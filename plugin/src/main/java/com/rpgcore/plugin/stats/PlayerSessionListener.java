package com.rpgcore.plugin.stats;

import com.rpgcore.plugin.RpgCorePlugin;
import com.rpgcore.plugin.data.PlayerData;
import com.rpgcore.plugin.progress.CounterType;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/**
 * Session lifecycle plus the default XP sources. Everything is event-driven -
 * no polling anywhere.
 */
public final class PlayerSessionListener implements Listener {

    private final RpgCorePlugin plugin;

    public PlayerSessionListener(RpgCorePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PlayerData data = plugin.players().get(player);
        boolean firstJoin = plugin.players().isFreshlyCreated(player);

        plugin.stats().recalculate(player, data);
        plugin.players().flush(player, data);

        plugin.parties().handleJoin(player);
        plugin.collections().load(player);
        plugin.achievements().load(player);
        plugin.titles().load(player);
        // Anything that concluded while they were away - a duel that timed
        // out, an auction that sold, a bid that was outbid - is waiting here.
        plugin.mailbox().collect(player);

        if (firstJoin) {
            player.sendMessage(ChatColor.GOLD + "[RPGCore] " + ChatColor.YELLOW
                    + "환영합니다! 레벨 1로 시작합니다. 스탯 포인트 "
                    + data.points() + "개를 보유 중입니다.");
            player.sendMessage(ChatColor.YELLOW + "  " + ChatColor.AQUA + "/stats"
                    + ChatColor.YELLOW + " 명령어로 스탯 창을 여세요.");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        // Before unloading: the plate is a scoreboard team, and the scoreboard
        // is saved with the world, so leaving it behind would accumulate one
        // dead team per player who ever logged in.
        plugin.nameplates().clear(event.getPlayer());
        plugin.collections().unload(event.getPlayer());
        plugin.achievements().unload(event.getPlayer());
        plugin.titles().unload(event.getPlayer());
        plugin.players().unload(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        // Attributes survive death, but re-applying keeps things consistent if
        // another plugin cleared modifiers on respawn.
        plugin.stats().recalculate(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMobKill(EntityDeathEvent event) {
        // Mob only: armour stands are LivingEntities too, and awarding XP for
        // those would be a one-block XP farm.
        if (!(event.getEntity() instanceof Mob)) {
            return;
        }
        Player killer = event.getEntity().getKiller();
        if (killer == null || killer.getGameMode() == GameMode.CREATIVE) {
            // Creative is excluded here for the same reason it is excluded
            // from mined blocks and caught fish: a goal that can be finished
            // from the creative menu is not a goal. Without this, mob-kill
            // achievements and the gold that comes with them were the one
            // progression an operator could hand themselves for free.
            return;
        }
        // LUCK is the one stat with nothing to point at in vanilla - the luck
        // attribute moves fishing loot tables and almost nothing else, which
        // made it a trap: a whole column of the stat screen that did not
        // reward investment. Here it buys a chance that a kill simply pays
        // twice, which is something a player can feel.
        boolean lucky = plugin.stats().rollLuck(killer);
        plugin.stats().awardXp(killer, doubled(plugin.rpgConfig().xpPerMobKill(), lucky));
        plugin.economy().give(killer, doubled(plugin.rpgConfig().goldPerMobKill(), lucky));
        plugin.achievements().bump(killer, CounterType.MOB_KILLS, 1);
        if (lucky) {
            killer.sendActionBar(LegacyComponentSerializer.legacyAmpersand()
                    .deserialize("&a✦ 행운! &f보상 2배"));
        }
    }

    /**
     * Doubles a reward without wrapping.
     *
     * Both rewards are operator-set ints with no ceiling, so a plain {@code * 2}
     * on a large one lands negative - and every consumer of these refuses
     * anything at or below zero, which turns a lucky kill into the only kill
     * that pays nothing at all.
     */
    private static int doubled(int amount, boolean lucky) {
        return lucky ? (int) Math.min((long) amount * 2L, Integer.MAX_VALUE) : amount;
    }
}
