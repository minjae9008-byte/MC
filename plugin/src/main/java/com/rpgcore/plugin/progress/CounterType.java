package com.rpgcore.plugin.progress;

import java.util.Locale;

/**
 * The lifetime tallies an achievement can be written against.
 *
 * Each one is mirrored to its own vanilla scoreboard objective, so it survives
 * restarts with the world, can be read for a player who is offline, and is
 * visible to <code>/scoreboard players get</code> like every other RPG value.
 *
 * LEVEL is the odd one out: it is not counted here but read from the player's
 * level, so "reach level 20" can be written the same way as "kill 1000 mobs".
 */
public enum CounterType {

    MOB_KILLS("mob-kills", "rpgcore.kills", "몬스터 처치"),
    BLOCKS_MINED("blocks-mined", "rpgcore.mined", "블록 채굴"),
    ORES_MINED("ores-mined", "rpgcore.ores", "광석 채굴"),
    LOGS_CHOPPED("logs-chopped", "rpgcore.logs", "원목 벌목"),
    FISH_CAUGHT("fish-caught", "rpgcore.fish", "물고기 낚기"),
    DUELS_WON("duels-won", "rpgcore.duels", "대결 승리"),
    GOLD_EARNED("gold-earned", "rpgcore.gold_total", "누적 골드"),
    COLLECTED("collected", "rpgcore.collected", "도감 등록"),
    // The economy's own tallies. Same treatment as the rest: mirrored, so an
    // achievement can be written against them and a leaderboard can rank them.
    TRADED("traded", "rpgcore.traded", "시장 거래액"),
    DIVIDENDS("dividends", "rpgcore.dividends", "받은 배당"),
    BUILT("built", "rpgcore.built", "청사진 블록"),
    LEVEL("level", null, "레벨");

    private final String id;
    private final String objective;
    private final String label;

    CounterType(String id, String objective, String label) {
        this.id = id;
        this.objective = objective;
        this.label = label;
    }

    /** Config-key lookup; null when the key names no counter. */
    public static CounterType byId(String id) {
        if (id == null) {
            return null;
        }
        String needle = id.trim().toLowerCase(Locale.ROOT);
        for (CounterType type : values()) {
            if (type.id.equals(needle)) {
                return type;
            }
        }
        return null;
    }

    public String id() {
        return id;
    }

    /** Null for LEVEL, which is read from the player's level instead. */
    public String objective() {
        return objective;
    }

    public String label() {
        return label;
    }
}
