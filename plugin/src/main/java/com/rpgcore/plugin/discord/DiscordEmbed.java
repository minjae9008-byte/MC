package com.rpgcore.plugin.discord;

import org.bukkit.ChatColor;

import java.util.ArrayList;
import java.util.List;

/**
 * One Discord embed, and the JSON for it.
 *
 * Embeds rather than plain content lines because the request was for
 * something readable: an embed carries a coloured bar down its left edge, so
 * a death and a level-up are told apart at a glance without reading a word,
 * and Discord groups the author line with its avatar instead of leaving a
 * wall of identical grey text.
 *
 * The JSON is written by hand. The plugin ships as a single jar with no
 * dependencies, and what goes out here is a handful of known keys - pulling
 * in a JSON library to write ten fields would cost more than it is worth.
 * Everything that reaches a value goes through {@link #escapeJson}.
 */
public final class DiscordEmbed {

    /** Discord's own limits. Past these the API rejects the whole message. */
    private static final int MAX_AUTHOR = 256;
    private static final int MAX_DESCRIPTION = 4096;
    private static final int MAX_FIELD_NAME = 256;
    private static final int MAX_FIELD_VALUE = 1024;
    private static final int MAX_FOOTER = 2048;
    /** Discord accepts at most ten embeds in one webhook message. */
    public static final int MAX_EMBEDS_PER_MESSAGE = 10;

    private String author;
    private String authorIcon;
    private String description;
    private String footer;
    private int color;
    private final List<Field> fields = new ArrayList<>();

    private record Field(String name, String value, boolean inline) { }

    public DiscordEmbed color(int rgb) {
        this.color = rgb;
        return this;
    }

    /** The headline. Sanitised and truncated here so callers cannot forget. */
    public DiscordEmbed author(String text, String iconUrl) {
        this.author = trim(clean(text), MAX_AUTHOR);
        this.authorIcon = iconUrl == null || iconUrl.isBlank() ? null : iconUrl;
        return this;
    }

    public DiscordEmbed description(String text) {
        this.description = trim(clean(text), MAX_DESCRIPTION);
        return this;
    }

    public DiscordEmbed field(String name, String value, boolean inline) {
        fields.add(new Field(trim(clean(name), MAX_FIELD_NAME),
                trim(clean(value), MAX_FIELD_VALUE), inline));
        return this;
    }

    public DiscordEmbed footer(String text) {
        this.footer = trim(clean(text), MAX_FOOTER);
        return this;
    }

    void appendJson(StringBuilder out) {
        out.append('{');
        boolean first = true;
        if (color != 0) {
            out.append("\"color\":").append(color);
            first = false;
        }
        if (author != null && !author.isEmpty()) {
            if (!first) {
                out.append(',');
            }
            out.append("\"author\":{\"name\":");
            escapeJson(out, author);
            if (authorIcon != null) {
                out.append(",\"icon_url\":");
                escapeJson(out, authorIcon);
            }
            out.append('}');
            first = false;
        }
        if (description != null && !description.isEmpty()) {
            if (!first) {
                out.append(',');
            }
            out.append("\"description\":");
            escapeJson(out, description);
            first = false;
        }
        if (!fields.isEmpty()) {
            if (!first) {
                out.append(',');
            }
            out.append("\"fields\":[");
            for (int i = 0; i < fields.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                Field f = fields.get(i);
                out.append("{\"name\":");
                escapeJson(out, f.name());
                out.append(",\"value\":");
                escapeJson(out, f.value());
                out.append(",\"inline\":").append(f.inline()).append('}');
            }
            out.append(']');
            first = false;
        }
        if (footer != null && !footer.isEmpty()) {
            if (!first) {
                out.append(',');
            }
            out.append("\"footer\":{\"text\":");
            escapeJson(out, footer);
            out.append('}');
        }
        out.append('}');
    }

    /**
     * Wraps embeds into one webhook body.
     *
     * {@code allowed_mentions} is emptied on every message. Without it any
     * text that reaches Discord can ping the whole server - and the text here
     * is player names, which on a Geyser server are whatever the player chose,
     * and config strings, which are whatever an operator pasted. A relay that
     * can be made to shout at everyone is a relay that gets switched off.
     */
    static String toMessage(List<DiscordEmbed> embeds, String username) {
        StringBuilder out = new StringBuilder(256 * embeds.size());
        out.append("{\"allowed_mentions\":{\"parse\":[]}");
        if (username != null && !username.isBlank()) {
            out.append(",\"username\":");
            escapeJson(out, trim(clean(username), 80));
        }
        out.append(",\"embeds\":[");
        for (int i = 0; i < embeds.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            embeds.get(i).appendJson(out);
        }
        out.append("]}");
        return out.toString();
    }

    /**
     * Makes arbitrary game text safe to show in Discord.
     *
     * Three separate problems, all of which show up with real player names:
     * colour codes would arrive as literal garbage, Discord markdown would let
     * a name with an underscore italicise the rest of the line, and a name
     * containing a backtick could open a code block that swallows everything
     * after it.
     */
    public static String clean(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String text = ChatColor.stripColor(raw);
        // Configs write colours with & rather than the section sign, and those
        // reach us unconverted when a value is passed through verbatim.
        text = stripAmpersandCodes(text);
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                // Only the characters Discord treats as markup anywhere in a
                // line. The list and heading markers (- # >) matter solely at
                // the start of one, and escaping those mid-sentence is worse
                // than leaving them: Discord prints the backslash.
                case '\\', '`', '*', '_', '~', '|' -> out.append('\\').append(c);
                // A newline inside an author line would break the layout; in a
                // description it is fine, so only control characters go.
                case '\r' -> { }
                default -> {
                    if (c >= ' ' || c == '\n') {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    private static String stripAmpersandCodes(String text) {
        if (text.indexOf('&') < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '&' && i + 1 < text.length() && isColorChar(text.charAt(i + 1))) {
                i++;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    private static boolean isColorChar(char c) {
        return (c >= '0' && c <= '9')
                || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')
                || (c >= 'k' && c <= 'o') || (c >= 'K' && c <= 'O')
                || c == 'r' || c == 'R';
    }

    private static String trim(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    /** Writes a JSON string literal, quotes included. */
    static void escapeJson(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
