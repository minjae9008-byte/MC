package com.rpgcore.plugin.platform;

import com.rpgcore.plugin.RpgCorePlugin;
import com.rpgcore.plugin.gui.StatsMenu;
import com.rpgcore.plugin.util.RpgScoreboard;
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
 * Cumulus versions must NOT be shaded into this jar - the API is provided by
 * Floodgate at runtime (see pom.xml).
 */
final class FloodgateBridge {

    private FloodgateBridge() {
    }

    static boolean isBedrockPlayer(UUID uuid) {
        return FloodgateApi.getInstance().isFloodgatePlayer(uuid);
    }

    static void openStatsForm(RpgCorePlugin plugin, Player player, RpgScoreboard board) {
        SimpleForm.Builder builder = SimpleForm.builder()
                .title("캐릭터 정보")
                .content(buildContent(player, board));

        for (StatsMenu.StatSlot slot : StatsMenu.STAT_SLOTS) {
            builder.button(slot.label() + "  +1   (현재 " + board.get(player, slot.objective()) + ")");
        }
        builder.button("닫기");

        builder.validResultHandler(response -> {
            int clicked = response.clickedButtonId();
            if (clicked < 0 || clicked >= StatsMenu.STAT_SLOTS.size()) {
                return;
            }
            StatsMenu.StatSlot slot = StatsMenu.STAT_SLOTS.get(clicked);
            // Form callbacks arrive off the main thread; command dispatch and
            // re-opening the form must both happen on the server thread.
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) {
                    return;
                }
                board.trigger(player, slot.triggerObjective());
            });
            // The datapack applies the trigger on its next tick, so re-open a
            // few ticks later to show refreshed numbers.
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    plugin.openStatsMenu(player);
                }
            }, 4L);
        });

        FloodgateApi.getInstance().sendForm(player.getUniqueId(), builder);
    }

    private static String buildContent(Player player, RpgScoreboard board) {
        return "Lv. " + board.get(player, "rpgcore.level") + "\n"
                + "XP " + board.get(player, "rpgcore.xp") + " / " + board.get(player, "rpgcore.xp_need") + "\n"
                + "HP " + board.get(player, "rpgcore.hp") + " / " + board.get(player, "rpgcore.hp_max") + "\n"
                + "무게 " + board.get(player, "rpgcore.weight") + " / " + board.get(player, "rpgcore.weight_max")
                + "  (부담 단계 " + board.get(player, "rpgcore.weight_tier") + "/3)\n"
                + "남은 스탯 포인트: " + board.get(player, "rpgcore.points");
    }
}
