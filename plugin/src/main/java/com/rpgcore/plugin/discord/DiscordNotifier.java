package com.rpgcore.plugin.discord;

import com.rpgcore.plugin.RpgCorePlugin;
import com.rpgcore.plugin.data.PlayerData;
import com.rpgcore.plugin.progress.Achievement;
import com.rpgcore.plugin.progress.TitleService;
import org.bukkit.entity.Player;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * What each event looks like once it reaches Discord.
 *
 * Kept apart from {@link DiscordService}, which only knows how to deliver
 * bytes. Everything about how a channel reads - which colour, how many lines,
 * what gets its own field - lives here.
 *
 * The guiding rule is one line per event. A Minecraft server produces a lot of
 * small news, and a relay that renders each death as a three-line block with a
 * title and a thumbnail turns an evening's play into an unreadable channel.
 * The colour bar carries the event type, the author line carries the sentence,
 * and anything more only appears when it genuinely adds something - the level
 * reached, the reward earned.
 */
public final class DiscordNotifier {

    // Discord's own palette, so the bars sit naturally next to the client UI.
    private static final int GREEN = 0x57F287;
    private static final int RED = 0xED4245;
    private static final int GREY = 0x99AAB5;
    private static final int BLURPLE = 0x5865F2;
    private static final int GOLD = 0xFEE75C;
    private static final int FUCHSIA = 0xEB459E;

    private final RpgCorePlugin plugin;
    private final long startedAt = System.currentTimeMillis();

    public DiscordNotifier(RpgCorePlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------ lifecycle

    public void serverStarted() {
        if (!plugin.rpgConfig().discordEvent("server-start")) {
            return;
        }
        plugin.discord().send(new DiscordEmbed()
                .color(GREEN)
                .author("🟢  서버가 열렸습니다", null)
                .description(plugin.getServer().getName() + " " + plugin.getServer().getMinecraftVersion()
                        + "  ·  RPGCore " + plugin.getPluginMeta().getVersion()));
    }

    /**
     * Built by the caller on the main thread during shutdown and handed to
     * {@link DiscordService#shutdown}, which waits for it to actually go out.
     */
    public DiscordEmbed serverStoppingEmbed() {
        if (!plugin.rpgConfig().discordEvent("server-stop")) {
            return null;
        }
        return new DiscordEmbed()
                .color(RED)
                .author("🔴  서버가 닫혔습니다", null)
                .description("가동 시간 " + uptime());
    }

    private String uptime() {
        Duration up = Duration.ofMillis(System.currentTimeMillis() - startedAt);
        long days = up.toDays();
        long hours = up.toHoursPart();
        long minutes = up.toMinutesPart();
        if (days > 0) {
            return days + "일 " + hours + "시간 " + minutes + "분";
        }
        if (hours > 0) {
            return hours + "시간 " + minutes + "분";
        }
        return Math.max(1, minutes) + "분";
    }

    // --------------------------------------------------------------- people

    public void playerJoined(Player player) {
        if (!plugin.rpgConfig().discordEvent("join")) {
            return;
        }
        plugin.discord().send(new DiscordEmbed()
                .color(GREEN)
                .author(player.getName() + " 님이 접속했습니다", avatar(player))
                .footer(onlineCount(0)));
    }

    public void playerLeft(Player player) {
        if (!plugin.rpgConfig().discordEvent("quit")) {
            return;
        }
        plugin.discord().send(new DiscordEmbed()
                .color(GREY)
                .author(player.getName() + " 님이 나갔습니다", avatar(player))
                // The quit event fires while the player is still counted.
                .footer(onlineCount(-1)));
    }

    private String onlineCount(int delta) {
        int online = Math.max(0, plugin.getServer().getOnlinePlayers().size() + delta);
        return "접속 중 " + online + "명";
    }

    /**
     * @param message vanilla's own death line, which already names the cause
     *                and the killer - rewriting it would only lose detail
     */
    public void playerDied(Player player, String message, PlayerData data) {
        if (!plugin.rpgConfig().discordEvent("death")) {
            return;
        }
        String text = message == null || message.isBlank()
                ? player.getName() + " 님이 사망했습니다"
                : message;
        DiscordEmbed embed = new DiscordEmbed()
                .color(RED)
                .author("💀  " + text, avatar(player));
        if (data != null) {
            embed.footer("Lv." + data.level()
                    + (player.getWorld() != null ? "  ·  " + player.getWorld().getName() : ""));
        }
        plugin.discord().send(embed);
    }

    // -------------------------------------------------------------- progress

    /**
     * Level-ups are the noisiest thing a levelling server produces - early
     * levels arrive every few minutes, per player. Posting all of them buries
     * everything else, so the operator sets a floor and a step and only the
     * levels worth a look are relayed.
     */
    public void levelUp(Player player, PlayerData data) {
        if (!plugin.rpgConfig().discordEvent("level-up")) {
            return;
        }
        int level = data.level();
        boolean atMax = plugin.stats().atMaxLevel(data);
        if (!relaysLevel(level, atMax)) {
            return;
        }
        plugin.discord().send(new DiscordEmbed()
                .color(BLURPLE)
                .author("⬆️  " + player.getName() + " 님이 Lv." + level + " 이 되었습니다"
                        + (atMax ? " (최대 레벨)" : ""), avatar(player)));
    }

    /**
     * Whether one level is worth a message.
     *
     * Split out from the rendering because it is the part with a rule in it,
     * and a rule that decides what an operator's channel looks like should be
     * checkable without a live player standing in a world.
     */
    public boolean relaysLevel(int level, boolean atMax) {
        if (level < plugin.rpgConfig().discordLevelMinimum()) {
            return false;
        }
        // The cap is always worth announcing, whatever the step says.
        if (atMax) {
            return true;
        }
        int step = plugin.rpgConfig().discordLevelStep();
        return step <= 1 || level % step == 0;
    }

    /**
     * Matches the in-game rule by default: the ones worth interrupting
     * everybody for. An operator who wants the small steps too sets
     * discord.all-achievements.
     */
    public boolean relaysAchievement(Achievement achievement) {
        return plugin.rpgConfig().discordAllAchievements()
                || achievement.firstOnly() || achievement.grantsTitle();
    }

    public void achievement(Player player, Achievement achievement) {
        if (!plugin.rpgConfig().discordEvent("achievement") || !relaysAchievement(achievement)) {
            return;
        }

        DiscordEmbed embed = new DiscordEmbed()
                .color(achievement.firstOnly() ? FUCHSIA : GOLD)
                .author((achievement.firstOnly() ? "🥇  " : "🏆  ") + player.getName() + " 님이 "
                        + achievement.display()
                        + (achievement.firstOnly() ? " 을(를) 서버 최초로 달성!" : " 을(를) 달성했습니다"),
                        avatar(player));
        if (!achievement.description().isBlank()) {
            embed.description(achievement.description());
        }
        String reward = reward(achievement);
        if (!reward.isEmpty()) {
            embed.footer(reward);
        }
        plugin.discord().send(embed);
    }

    private String reward(Achievement achievement) {
        StringBuilder out = new StringBuilder();
        if (achievement.rewardGold() > 0) {
            out.append(achievement.rewardGold()).append(plugin.rpgConfig().goldSymbol());
        }
        if (achievement.rewardXp() > 0) {
            if (out.length() > 0) {
                out.append("  ·  ");
            }
            out.append("XP ").append(achievement.rewardXp());
        }
        if (achievement.grantsTitle()) {
            TitleService.Title title = plugin.titles().byId(achievement.id());
            if (title != null) {
                if (out.length() > 0) {
                    out.append("  ·  ");
                }
                out.append("칭호 ").append(title.display());
            }
        }
        return out.toString();
    }

    // ---------------------------------------------------------------- shared

    /**
     * The player's head, which is what makes a channel scannable - you read
     * the faces before the words. Templated because the service that renders
     * them is somebody else's, and an operator may want a different one or
     * none at all.
     */
    private String avatar(Player player) {
        String template = plugin.rpgConfig().discordAvatarUrl();
        if (template.isBlank()) {
            return null;
        }
        return template
                .replace("%uuid%", player.getUniqueId().toString())
                // Bedrock names arrive through Floodgate with spaces and
                // punctuation in them, which would not survive being pasted
                // into a URL unencoded.
                .replace("%player%", URLEncoder.encode(player.getName(), StandardCharsets.UTF_8));
    }
}
