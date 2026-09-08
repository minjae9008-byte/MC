package com.rpgcore.plugin.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;

/**
 * Thin read/write bridge to the same "main" scoreboard the datapack's
 * `/scoreboard` commands operate on. Every objective name here must match an
 * objective created in datapack/data/rpgcore/function/load.mcfunction.
 */
public final class RpgScoreboard {

    public int get(Player player, String objective) {
        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        Objective obj = board.getObjective(objective);
        if (obj == null) {
            return 0;
        }
        Score score = obj.getScore(player.getName());
        return score.isScoreSet() ? score.getScore() : 0;
    }

    /**
     * Fires the matching /trigger objective so the datapack (the real owner
     * of stat-allocation logic, XP, etc.) performs the actual change. The
     * plugin never mutates rpgcore.* scores directly for gameplay logic -
     * only the datapack does, so the two can never disagree.
     */
    public void trigger(Player player, String triggerObjective) {
        player.performCommand("trigger " + triggerObjective + " add 1");
    }
}
