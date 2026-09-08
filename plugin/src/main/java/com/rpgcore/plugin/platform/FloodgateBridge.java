package com.rpgcore.plugin.platform;

import com.rpgcore.plugin.RpgCorePlugin;
import com.rpgcore.plugin.data.PlayerData;
import com.rpgcore.plugin.stats.StatType;
import org.bukkit.entity.Player;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.UUID;

/**
 * The only class in the project that touches Floodgate/Cumulus types. It is
 * loaded lazily by {@link BedrockPlatform}, after the "floodgate" plugin has
 * been confirmed present, so servers without Geyser never resolve these
 * imports. Any API mismatch surfaces as a Throwable in BedrockPlatform, which
 * then permanently falls back to the chest GUI.
 *
 * Cumulus must NOT be shaded into this jar - the API is provided by Floodgate
 * at runtime (see pom.xml).
 */
final class FloodgateBridge {

    private FloodgateBridge() {
    }

    static boolean isBedrockPlayer(UUID uuid) {
        return FloodgateApi.getInstance().isFloodgatePlayer(uuid);
    }

    static void openStatsForm(RpgCorePlugin plugin, Player player) {
        PlayerData data = plugin.players().get(player);

        SimpleForm.Builder builder = SimpleForm.builder()
                .title("캐릭터 정보")
                .content(buildContent(player, data));

        for (StatType type : StatType.values()) {
            builder.button(type.label() + "  +1   (현재 " + data.stat(type) + ")");
        }
        builder.button("닫기");

        builder.validResultHandler(response -> {
            int clicked = response.clickedButtonId();
            StatType[] types = StatType.values();
            if (clicked < 0 || clicked >= types.length) {
                return;
            }
            StatType type = types[clicked];
            // Form callbacks arrive off the main thread; stat changes and the
            // re-open must both run on the server thread.
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) {
                    return;
                }
                if (plugin.stats().allocate(player, type)) {
                    plugin.openStatsMenu(player);
                }
            });
        });

        FloodgateApi.getInstance().sendForm(player.getUniqueId(), builder);
    }

    private static String buildContent(Player player, PlayerData data) {
        return "Lv. " + data.level() + "\n"
                + "XP " + data.xp() + " / " + data.xpNeed() + "\n"
                + "HP " + (int) Math.ceil(player.getHealth()) + " / " + data.maxHealth() + "\n"
                + "무게 " + data.weight() + " / " + data.weightMax()
                + "  (" + data.loadPercent() + "%, 부담 단계 " + data.weightTier() + "/3)\n"
                + "남은 스탯 포인트: " + data.points();
    }
}
