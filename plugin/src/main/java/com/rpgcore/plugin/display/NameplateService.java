package com.rpgcore.plugin.display;

import com.rpgcore.plugin.RpgCorePlugin;
import com.rpgcore.plugin.data.PlayerData;
import com.rpgcore.plugin.job.RpgJob;
import com.rpgcore.plugin.party.Party;
import com.rpgcore.plugin.progress.TitleService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The name a player wears: the plate above their head and their row in the
 * player list.
 *
 * Both are built from the same four segments (party, title, level, job) so the
 * two can never disagree. The plate is a vanilla scoreboard team prefix/suffix
 * rather than a floating entity: one team per player needs no per-tick packets
 * and - unlike display entities or armour stands - Geyser translates it, so
 * Bedrock players see the same plate Java players do.
 *
 * Rendering is cheap, writing is not (a team change is a packet to everyone),
 * so the rendered text is cached and only written when it actually changes.
 */
public final class NameplateService {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    public static final String NAMEPLATE = "nameplate";
    public static final String TABLIST = "tablist";

    /**
     * The segment order, hoisted out of the two builders below.
     *
     * They run four times per player per HUD pass - plate prefix and suffix,
     * player-list prefix and suffix - so allocating these two arrays inside
     * them made a hundred players a steady four hundred throwaway arrays a
     * second, for a pair of constants.
     */
    private static final String[] PREFIX_SEGMENTS = {"party", "title"};
    private static final String[] SUFFIX_SEGMENTS = {"level", "job"};

    private final RpgCorePlugin plugin;
    /** Last text written per player, so an unchanged plate costs one compare. */
    private final Map<UUID, String> written = new HashMap<>();

    public NameplateService(RpgCorePlugin plugin) {
        this.plugin = plugin;
    }

    /** Called once a second from the HUD task; writes only on a real change. */
    public void refresh(Player player, PlayerData data) {
        boolean plate = plugin.rpgConfig().nameplateEnabled();
        boolean tab = plugin.rpgConfig().tablistEnabled();
        if (!plate && !tab) {
            // Turning the feature off mid-session has to take effect, not
            // leave everyone wearing the last plate they were handed.
            clear(player);
            return;
        }

        String prefix = plate ? prefix(NAMEPLATE, player, data) : "";
        String suffix = plate ? suffix(NAMEPLATE, player, data) : "";
        String tabPrefix = tab ? prefix(TABLIST, player, data) : "";
        String tabSuffix = tab ? suffix(TABLIST, player, data) : "";

        // Newline separated: it cannot appear in any of the four, so two
        // different splits can never render as the same signature.
        String signature = String.join("\n", prefix, suffix, tabPrefix, tabSuffix);
        if (signature.equals(written.get(player.getUniqueId()))) {
            return;
        }
        written.put(player.getUniqueId(), signature);

        if (plate) {
            applyTeam(player, prefix, suffix);
        } else {
            // Only the player list is on: the plate has to come off, or the
            // last one written would hang above the player forever.
            removeTeam(player);
        }
        player.playerListName(tab
                ? LEGACY.deserialize(tabPrefix + player.getName() + tabSuffix)
                : null);
    }

    /**
     * Takes the plate off again. Also called on quit, so the per-player teams
     * do not pile up inside the world's saved scoreboard.
     */
    public void clear(Player player) {
        written.remove(player.getUniqueId());
        player.playerListName(null);
        removeTeam(player);
    }

    private void removeTeam(Player player) {
        Scoreboard board = board();
        if (board == null) {
            return;
        }
        Team team = board.getTeam(teamName(player));
        // Only a team holding this player and nobody else: a team of the same
        // name with other members in it belongs to something else, and taking
        // that away would be worse than leaving a plate on.
        if (team != null && team.getEntries().equals(Set.of(player.getName()))) {
            team.unregister();
        }
    }

    private void applyTeam(Player player, String prefix, String suffix) {
        Scoreboard board = board();
        if (board == null) {
            return;
        }
        String name = teamName(player);
        Team team = board.getTeam(name);
        if (team == null) {
            team = board.registerNewTeam(name);
            // A team is normally a PvP construct; this one exists only to
            // carry text, so the side effects of being on one are turned off.
            // Party friendly fire stays PartyListener's job.
            team.setAllowFriendlyFire(true);
            team.setCanSeeFriendlyInvisibles(false);
        }
        if (!team.hasEntry(player.getName())) {
            team.addEntry(player.getName());
        }
        team.prefix(prefix.isEmpty() ? Component.empty() : LEGACY.deserialize(prefix));
        team.suffix(suffix.isEmpty() ? Component.empty() : LEGACY.deserialize(suffix));
    }

    /**
     * What goes in front of the name: the party tag, then the earned title.
     * The separating spaces belong here rather than in the format strings - a
     * trailing space in config is invisible to read and the first thing an
     * editor strips.
     */
    private String prefix(String where, Player player, PlayerData data) {
        StringBuilder out = new StringBuilder();
        for (String what : PREFIX_SEGMENTS) {
            String part = segment(where, what, player, data);
            if (!part.isEmpty()) {
                out.append(part).append(' ');
            }
        }
        return out.toString();
    }

    /**
     * The parts that follow the name, in a fixed order and space separated, so
     * switching one off never leaves a gap or a dangling separator behind.
     */
    private String suffix(String where, Player player, PlayerData data) {
        List<String> parts = new ArrayList<>(2);
        for (String what : SUFFIX_SEGMENTS) {
            String part = segment(where, what, player, data);
            if (!part.isEmpty()) {
                parts.add(part);
            }
        }
        return parts.isEmpty() ? "" : " " + String.join(" ", parts);
    }

    /**
     * One segment, or "" when this surface does not show it or the player has
     * nothing to show - someone with no party gets no party tag rather than an
     * empty pair of brackets.
     */
    private String segment(String where, String what, Player player, PlayerData data) {
        if (!plugin.rpgConfig().displayShows(where, what)) {
            return "";
        }
        String format = plugin.rpgConfig().displayFormat(what);
        if (format.isEmpty()) {
            return "";
        }
        switch (what) {
            case "party" -> {
                if (!plugin.rpgConfig().partyEnabled()) {
                    return "";
                }
                Party party = plugin.parties().partyOf(player);
                return party == null ? "" : format.replace("%party%", party.name());
            }
            case "title" -> {
                TitleService.Title title = plugin.titles().worn(player);
                if (title == null) {
                    return "";
                }
                // Same reasoning as the job segment below: the text comes out
                // of config already translated to section signs.
                return format.replace("%title%", title.display().replace('\u00a7', '&')) + "&r";
            }
            case "level" -> {
                return format.replace("%level%", String.valueOf(data.level()));
            }
            case "job" -> {
                RpgJob job = plugin.jobs().byId(data.jobId());
                if (job == null) {
                    return "";
                }
                // Job names arrive with their colour codes already translated
                // to section signs, which this serializer would render as
                // literal text; back to ampersands so they stay colours. The
                // trailing reset keeps the next segment's colour honest, since
                // whatever the format set before %job% is overridden from the
                // job name onwards.
                return format.replace("%job%", job.displayName().replace('\u00a7', '&')) + "&r";
            }
            default -> {
                return "";
            }
        }
    }

    /**
     * Player names are unique and at most 16 characters, which is exactly what
     * a team name has to be.
     */
    private String teamName(Player player) {
        return player.getName();
    }

    private Scoreboard board() {
        return Bukkit.getScoreboardManager() == null ? null : Bukkit.getScoreboardManager().getMainScoreboard();
    }
}
