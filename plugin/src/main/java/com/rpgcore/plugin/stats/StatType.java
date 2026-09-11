package com.rpgcore.plugin.stats;

import org.bukkit.Material;

/**
 * The allocatable base stats. Adding a stat here is enough for it to appear in
 * the chest GUI, the Bedrock form, the HUD and the scoreboard mirror - only its
 * derived effect needs wiring in {@link StatsService#recalculate}.
 */
public enum StatType {

    STR("STR", "rpgcore.str", Material.IRON_SWORD, "공격력 / 최대 소지무게"),
    DEX("DEX", "rpgcore.dex", Material.FEATHER, "공격 속도"),
    VIT("VIT", "rpgcore.vit", Material.GOLDEN_APPLE, "최대 체력"),
    AGI("AGI", "rpgcore.agi", Material.RABBIT_FOOT, "이동속도 / 점프력"),
    LUCK("LUCK", "rpgcore.luck", Material.EMERALD, "행운");

    private final String label;
    private final String objective;
    private final Material icon;
    private final String description;

    StatType(String label, String objective, Material icon, String description) {
        this.label = label;
        this.objective = objective;
        this.icon = icon;
        this.description = description;
    }

    /** Case-insensitive lookup for config keys; null when unknown. */
    public static StatType byName(String name) {
        for (StatType type : values()) {
            if (type.name().equalsIgnoreCase(name)) {
                return type;
            }
        }
        return null;
    }

    public String label() {
        return label;
    }

    public String objective() {
        return objective;
    }

    public Material icon() {
        return icon;
    }

    public String description() {
        return description;
    }
}
