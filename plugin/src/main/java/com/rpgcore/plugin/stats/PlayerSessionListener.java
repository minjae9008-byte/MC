package com.rpgcore.plugin.stats;

import com.rpgcore.plugin.RpgCorePlugin;
import com.rpgcore.plugin.data.PlayerData;
import com.rpgcore.plugin.progress.CounterType;
import org.bukkit.ChatColor;
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
        plugin.guilds().handleJoin(player);
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
        if (killer == null) {
            return;
        }
        plugin.stats().awardXp(killer, plugin.rpgConfig().xpPerMobKill());
        plugin.economy().give(killer, plugin.rpgConfig().goldPerMobKill());
        plugin.achievements().bump(killer, CounterType.MOB_KILLS, 1);
    }
}
