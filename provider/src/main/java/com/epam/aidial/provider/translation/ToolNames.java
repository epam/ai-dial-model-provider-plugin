package com.epam.aidial.provider.translation;

import com.google.common.hash.Hashing;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The tool names of one request as the Responses API takes them. OpenAI allows {@code ^[a-zA-Z0-9_-]{1,64}$}, Gemini
 * also wants a leading letter or underscore, so a name that is not {@code ^[a-zA-Z_][a-zA-Z0-9_-]{0,63}$} is replaced
 * by an alias: the name made of allowed characters (55 at most, a {@code t_} before one that does not start with a
 * letter or underscore), {@code _} and 8 hex digits of the name's SHA-256. The alias is remembered, so that the name
 * comes back in the answer. Not shared between requests.
 */
final class ToolNames {

    private static final Pattern CONFORMING = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_-]{0,63}$");
    private static final Pattern DISALLOWED_CHAR = Pattern.compile("[^a-zA-Z0-9_-]");
    private static final Pattern ALLOWED_LEAD = Pattern.compile("^[a-zA-Z_]");

    private static final int MAX_LENGTH = 64;
    private static final int DIGEST_LENGTH = 8;
    private static final int HEAD_LENGTH = MAX_LENGTH - DIGEST_LENGTH - 1;

    private final Map<String, String> originals = new HashMap<>();

    String toUpstream(String name) {
        if (name.isEmpty() || CONFORMING.matcher(name).matches()) {
            return name;
        }
        String alias = alias(name);
        originals.put(alias, name);
        return alias;
    }

    String toClient(String name) {
        return originals.getOrDefault(name, name);
    }

    private static String alias(String name) {
        String digest = Hashing.sha256()
                .hashString(name, StandardCharsets.UTF_8)
                .toString()
                .substring(0, DIGEST_LENGTH);
        String head = DISALLOWED_CHAR.matcher(name).replaceAll("_");
        if (!ALLOWED_LEAD.matcher(head).find()) {
            head = "t_" + head;
        }
        return head.substring(0, Math.min(head.length(), HEAD_LENGTH)) + "_" + digest;
    }
}
