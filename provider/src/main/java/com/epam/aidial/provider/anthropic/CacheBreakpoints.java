package com.epam.aidial.provider.anthropic;

import com.epam.aidial.provider.api.BodyPath;
import com.epam.aidial.provider.api.CacheBreakpoint;
import com.epam.aidial.provider.json.BodyFields;
import com.epam.aidial.provider.json.Json;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** The candidates are collected in the one {@link BodyFields#parse} pass, so the prompt is not parsed again. */
final class CacheBreakpoints implements BodyFields.MemberScanner {

    private static final int DEFAULT_TTL_SECONDS = 5 * 60; // Claude's default cache TTL

    enum Section {
        TOOLS,
        SYSTEM,
        MESSAGE_CONTENT
    }

    /**
     * @param message the message's index for {@link Section#MESSAGE_CONTENT}, else 0
     * @param marked  carries an ephemeral cache_control
     */
    record Candidate(Section section, int message, int index, boolean marked, @Nullable String ttl) {

        BodyPath path() {
            BodyPath root = BodyPath.ROOT;
            return switch (section) {
                case TOOLS -> root.member("tools").index(index);
                case SYSTEM -> root.member("system").index(index);
                case MESSAGE_CONTENT ->
                    root.member("messages").index(message).member("content").index(index);
            };
        }
    }

    private final List<Candidate> candidates = new ArrayList<>();

    @Override
    public boolean scan(String name, JsonParser parser) throws IOException {
        switch (name) {
            case "tools" -> blocks(parser, Section.TOOLS, 0);
            case "system" -> blocks(parser, Section.SYSTEM, 0);
            case "messages" -> messages(parser);
            default -> {
                return false;
            }
        }
        return true;
    }

    /** Null when the request carries no breakpoint. */
    @Nullable
    CacheBreakpoint breakpoint(BodyFields fields) {
        if (fields.isNull("messages")) {
            return null;
        }
        Candidate last = null;
        int ttl = 0;
        for (Candidate c : candidates) {
            if (c.marked()) {
                ttl = Math.max(ttl, ttlSeconds(c.ttl()));
                last = c;
            }
        }
        // Automatic caching: Anthropic caches the longest cacheable prefix, so the last candidate is the breakpoint.
        byte[] top = fields.get("cache_control");
        if (!candidates.isEmpty() && BodyFields.truthy(top)) {
            if (!(Json.parseOrNull(top) instanceof Map<?, ?> control)) {
                return null;
            }
            ttl = Math.max(ttl, ttlSeconds(control.get("ttl") instanceof String s ? s : null));
            last = candidates.getLast();
        }
        return last == null ? null : new CacheBreakpoint(last.path(), ttl);
    }

    static int ttlSeconds(@Nullable String ttl) {
        if (ttl == null || ttl.isEmpty()) {
            return DEFAULT_TTL_SECONDS;
        }
        char unit = ttl.charAt(ttl.length() - 1);
        long unitSeconds =
                switch (unit) {
                    case 'h' -> 3600;
                    case 'm' -> 60;
                    case 's' -> 1;
                    default -> 0;
                };
        if (unitSeconds == 0) {
            return DEFAULT_TTL_SECONDS;
        }
        try {
            int amount = Integer.parseInt(ttl.substring(0, ttl.length() - 1).strip());
            return amount > 0 ? (int) Math.min(Integer.MAX_VALUE, amount * unitSeconds) : DEFAULT_TTL_SECONDS;
        } catch (NumberFormatException e) {
            return DEFAULT_TTL_SECONDS;
        }
    }

    /**
     * A scalar where an array is expected counts as a one-element array, the way DIAL Core hashes it; null counts as
     * none.
     */
    private void blocks(JsonParser parser, Section section, int message) throws IOException {
        JsonToken token = parser.currentToken();
        if (token == JsonToken.VALUE_NULL) {
            return;
        }
        if (token != JsonToken.START_ARRAY) {
            candidates.add(new Candidate(section, message, 0, false, null));
            parser.skipChildren();
            return;
        }
        for (int i = 0; parser.nextToken() != JsonToken.END_ARRAY; i++) {
            candidates.add(block(parser, section, message, i));
        }
    }

    /** Leaves the parser on the block's last token. */
    private static Candidate block(JsonParser parser, Section section, int message, int index) throws IOException {
        if (parser.currentToken() != JsonToken.START_OBJECT) {
            parser.skipChildren();
            return new Candidate(section, message, index, false, null);
        }
        CacheControl control = new CacheControl(null, null);
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            String name = parser.currentName();
            parser.nextToken();
            if (name.equals("cache_control") && parser.currentToken() == JsonToken.START_OBJECT) {
                control = cacheControl(parser);
            } else {
                parser.skipChildren();
            }
        }
        boolean marked = "ephemeral".equals(control.type());
        return new Candidate(section, message, index, marked, marked ? control.ttl() : null);
    }

    private record CacheControl(@Nullable String type, @Nullable String ttl) {}

    /** Leaves the parser on the object's last token. */
    private static CacheControl cacheControl(JsonParser parser) throws IOException {
        String type = null;
        String ttl = null;
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            String member = parser.currentName();
            JsonToken value = parser.nextToken();
            if (value == JsonToken.VALUE_STRING && member.equals("type")) {
                type = parser.getText();
            } else if (value == JsonToken.VALUE_STRING && member.equals("ttl")) {
                ttl = parser.getText();
            } else {
                parser.skipChildren();
            }
        }
        return new CacheControl(type, ttl);
    }

    /** A non-array messages yields none, as DIAL Core does not normalize it either. */
    private void messages(JsonParser parser) throws IOException {
        if (parser.currentToken() != JsonToken.START_ARRAY) {
            parser.skipChildren();
            return;
        }
        for (int i = 0; parser.nextToken() != JsonToken.END_ARRAY; i++) {
            if (parser.currentToken() != JsonToken.START_OBJECT) {
                parser.skipChildren();
                continue;
            }
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String name = parser.currentName();
                parser.nextToken();
                if (name.equals("content")) {
                    blocks(parser, Section.MESSAGE_CONTENT, i);
                } else {
                    parser.skipChildren();
                }
            }
        }
    }
}
