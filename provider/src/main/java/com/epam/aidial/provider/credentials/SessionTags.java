package com.epam.aidial.provider.credentials;

import com.epam.aidial.provider.api.RequestScope;
import com.epam.aidial.provider.core.Config;
import com.epam.aidial.provider.core.DialApi;
import com.epam.aidial.provider.json.Json;
import com.epam.aidial.provider.upstream.ClientGoneException;
import com.epam.aidial.provider.upstream.CredentialsUnavailableException;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sts.model.Tag;

/**
 * AWS_SESSION_TAGS maps each tag key to its value source: the request's model ("Bedrock.modelId") or a path into the
 * DIAL user info ("UserInfo.&lt;path&gt;"). In this process it is always an object of strings.
 */
public final class SessionTags {

    static final String MODEL_SOURCE = "Bedrock.modelId";
    static final String USER_INFO_PREFIX = "UserInfo.";
    static final String PROJECT_SOURCE = "UserInfo.project";

    // AWS's limits (https://docs.aws.amazon.com/IAM/latest/UserGuide/id_session-tags.html).
    static final int MAX_TAGS = 50;
    static final int MAX_TAG_KEY = 128;
    static final int MAX_TAG_VALUE = 256;
    static final int MAX_ROLE_SESSION_NAME = 64;

    private static final Logger log = LoggerFactory.getLogger(SessionTags.class);

    // A sign and digits; ASCII digits only: an index such as "1_0" is not an integer here (README "Checks").
    private static final Pattern ARRAY_INDEX = Pattern.compile("[+-]?[0-9]+");

    private final DialApi dial;
    private final List<String> modelKeys = new ArrayList<>();
    private final List<UserInfoTag> userInfoTags = new ArrayList<>();

    /** An entry of an unknown source is warned of here, once, not per request. */
    SessionTags(Map<String, String> entries, DialApi dial) {
        this.dial = dial;
        entries.forEach((key, source) -> {
            if (source.equals(MODEL_SOURCE)) {
                modelKeys.add(key);
            } else if (source.startsWith(USER_INFO_PREFIX)) {
                userInfoTags.add(new UserInfoTag(key, source));
            } else {
                log.atWarn()
                        .addKeyValue("key", key)
                        .addKeyValue("source", source)
                        .log("Skipping AWS STS session tag: unknown value source");
            }
        });
    }

    /** Null when AWS_SESSION_TAGS tags nothing. */
    public static @Nullable SessionTags of(Config config, DialApi dial) {
        return config.sessionTags() == null ? null : new SessionTags(config.sessionTags(), dial);
    }

    /** Whether a tag takes its value from the DIAL user info. */
    public static boolean usesUserInfo(Config config) {
        return config.sessionTags() != null
                && config.sessionTags().values().stream().anyMatch(source -> source.startsWith(USER_INFO_PREFIX));
    }

    record Tagged(String key, String source, String value) {}

    private record UserInfoTag(String key, String source) {

        String path() {
            return source.substring(USER_INFO_PREFIX.length());
        }
    }

    /**
     * model is null when the request has none (a route without a body, or a body whose model is no string). Fails with
     * a {@link CredentialsUnavailableException}, or ClientGoneException when the client goes.
     *
     * @param headers the request's headers: the user info is that of their API key
     * @param scope   the request: the user info is fetched through its core
     */
    public Future<AwsCredentials.Session> resolve(MultiMap headers, RequestScope scope, @Nullable String model) {
        String modelValue = modelValue(model);
        Future<Map<String, Object>> info = userInfoTags.isEmpty() ? Future.succeededFuture() : userInfo(headers, scope);
        return info.map(user -> fit(tagged(modelValue, user)))
                .recover(e -> Future.failedFuture(
                        e instanceof ClientGoneException
                                ? e
                                : new CredentialsUnavailableException(
                                        CredentialsUnavailableException.Source.SESSION_TAGS, e)));
    }

    private @Nullable String modelValue(@Nullable String model) {
        if (model == null && !modelKeys.isEmpty()) {
            log.atWarn()
                    .addKeyValue("keys", String.join(", ", modelKeys))
                    .log("Skipping the Bedrock.modelId AWS STS session tag(s); the request carries no model id");
        }
        return model;
    }

    /**
     * A transport failure is retried, a status is not. Null when not fetched: no api-key, or a failure (logged); an
     * empty api-key fails the request.
     */
    private Future<Map<String, Object>> userInfo(MultiMap headers, RequestScope scope) {
        Future<Void> aborted = scope.aborted();
        String key = headers.get("Api-Key");
        if (key == null) {
            log.warn("Skipping UserInfo AWS STS session tags; the request carries no DIAL API key");
            return Future.succeededFuture();
        }
        if (key.isEmpty()) {
            // the DIAL client refuses an empty key
            return Future.failedFuture(new IllegalStateException("empty api-key header"));
        }
        return dial.get(scope, DialApi.USER_INFO, key, DialApi.Retry.TRANSPORT)
                .map(body -> parseUserInfo(body.getBytes()))
                .recover(ClientGoneException.unlessGone(aborted, e -> {
                    log.atWarn()
                            .addKeyValue("error", e.getMessage())
                            .log("Skipping UserInfo AWS STS session tags; failed to fetch DIAL user info");
                    return Future.succeededFuture();
                }));
    }

    private List<Tagged> tagged(@Nullable String model, @Nullable Map<String, Object> user) {
        List<Tagged> tags = new ArrayList<>();
        if (model != null) {
            modelKeys.forEach(k -> tags.add(new Tagged(k, MODEL_SOURCE, model)));
        }
        if (user != null) {
            for (UserInfoTag tag : userInfoTags) {
                String v = resolvePath(user, tag.path());
                if (v != null) {
                    tags.add(new Tagged(tag.key(), tag.source(), v));
                }
            }
        }
        return tags;
    }

    static Map<String, Object> parseUserInfo(byte[] body) {
        if (!(Json.parseOrNull(body) instanceof Map<?, ?> top)) {
            throw new IllegalArgumentException("the user info is not a JSON object");
        }
        Object roles = top.get("roles");
        Object project = top.get("project");
        Object claims = top.get("userClaims");
        boolean ok = roles instanceof List<?> list && list.stream().allMatch(r -> r instanceof String);
        if (!ok || project != null && !(project instanceof String) || claims != null && !(claims instanceof Map)) {
            throw new IllegalArgumentException(
                    "the user info does not have roles as strings, project as a string and userClaims as an object");
        }
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("roles", roles);
        info.put("project", project);
        info.put("userClaims", claims);
        top.forEach((k, v) -> info.putIfAbsent((String) k, v));
        return info;
    }

    /**
     * Each "."-separated segment is a member name or an array index (negative from the end). A string is returned as
     * it is, anything else as its JSON text; null when unresolved, which is logged, except under a project or
     * userClaims that is null.
     */
    static String resolvePath(Map<String, Object> info, String path) {
        if (path.isEmpty()) {
            return null;
        }
        Object node = info;
        for (String segment : path.split("\\.", -1)) {
            String problem = null;
            if (node instanceof Map<?, ?> object) {
                if (!object.containsKey(segment)) {
                    problem = "no such member";
                }
                node = object.get(segment);
            } else if (node instanceof List<?> list) {
                // whitespace around the index is allowed
                String digits = segment.strip();
                if (!ARRAY_INDEX.matcher(digits).matches()) {
                    problem = "not an integer index";
                } else {
                    long i = digits.length() > 18 ? Long.MAX_VALUE : Long.parseLong(digits);
                    if (i < 0) {
                        i += list.size();
                    }
                    if (i < 0 || i >= list.size()) {
                        problem = "index out of range";
                    } else {
                        node = list.get((int) i);
                    }
                }
            } else {
                problem = "cannot index into a scalar";
            }
            if (problem != null) {
                String field = path.split("\\.", 2)[0];
                if (info.get(field) == null && (field.equals("project") || field.equals("userClaims"))) {
                    return null;
                }
                log.atWarn()
                        .addKeyValue("path", path)
                        .addKeyValue("error", problem)
                        .log("Skipping unresolved AWS STS session tags path");
                return null;
            }
        }
        return Json.text(node);
    }

    /**
     * Each change is logged by key or source, never by value. The session is named after the first project tag sent.
     */
    static AwsCredentials.Session fit(List<Tagged> tags) {
        List<Tag> sent = new ArrayList<>();
        List<String> changedKeys = new ArrayList<>();
        List<String> changedValues = new ArrayList<>();
        List<String> emptyKeys = new ArrayList<>();
        List<String> collisions = new ArrayList<>();
        Set<String> taken = new HashSet<>();
        String project = null;
        for (int i = 0; i < tags.size(); i++) {
            Tagged t = tags.get(i);
            if (sent.size() == MAX_TAGS) {
                List<String> omitted =
                        tags.subList(i, tags.size()).stream().map(Tagged::key).toList();
                log.atWarn()
                        .addKeyValue("keys", String.join(", ", omitted))
                        .log("AWS STS session tags entry cap reached; omitted configured tag(s)");
                break;
            }
            String k = fitTag(t.key(), MAX_TAG_KEY);
            String v = fitTag(t.value(), MAX_TAG_VALUE);
            if (!k.equals(t.key())) {
                changedKeys.add(t.key());
            }
            if (!v.equals(t.value())) {
                changedValues.add(t.key());
            }
            if (k.isEmpty()) {
                emptyKeys.add(t.source());
                continue;
            }
            if (taken.contains(k)) {
                collisions.add(t.key());
                k = dedupeKey(k, taken);
            }
            taken.add(k);
            sent.add(Tag.builder().key(k).value(v).build());
            if (t.source().equals(PROJECT_SOURCE) && project == null) {
                project = v;
            }
        }
        warn("Sanitized AWS STS session tags key(s)", "keys", changedKeys);
        warn("Sanitized AWS STS session tags value(s)", "keys", changedValues);
        warn("Dropped AWS STS session tags with an empty key", "sources", emptyKeys);
        warn("Postfixed AWS STS session tags whose sanitized key collides with an earlier entry", "keys", collisions);
        return new AwsCredentials.Session(roleSessionName(project), sent);
    }

    private static void warn(String message, String attribute, List<String> list) {
        if (!list.isEmpty()) {
            log.atWarn().addKeyValue(attribute, String.join(", ", list)).log(message);
        }
    }

    /** AWS allows [\p{L}\p{Z}\p{N}_.:/=+\-@]; any other character becomes "_". Cut to max characters. */
    static String fitTag(String s, int max) {
        StringBuilder out = new StringBuilder();
        int count = 0;
        for (int cp : s.codePoints().toArray()) {
            if (count == max) {
                break;
            }
            boolean allowed = isLetterOrNumber(cp) || isSeparator(cp) || "_.:/=+-@".indexOf(cp) >= 0;
            out.appendCodePoint(allowed ? cp : '_');
            count++;
        }
        return out.toString();
    }

    static String dedupeKey(String k, Set<String> taken) {
        int[] cps = k.codePoints().toArray();
        for (int i = 1; i <= MAX_TAGS; i++) {
            String suffix = "_" + i;
            String c = new String(cps, 0, Math.min(cps.length, MAX_TAG_KEY - suffix.length())) + suffix;
            if (!taken.contains(c)) {
                return c;
            }
        }
        return k;
    }

    /**
     * "Project_&lt;project&gt;" fitted to STS's [\w+=,.@-] (Unicode letters and numbers, and "_") and 64 characters.
     * Without a project (null, empty or the text "null") it is the default name.
     */
    static String roleSessionName(String project) {
        if (project == null || project.isEmpty() || project.equals("null")) {
            return AwsCredentials.DEFAULT_ROLE_SESSION_NAME;
        }
        int[] cps = ("Project_" + project)
                .codePoints()
                .map(c -> isLetterOrNumber(c) || "_+=,.@-".indexOf(c) >= 0 ? c : '_')
                .toArray();
        return new String(cps, 0, Math.min(cps.length, MAX_ROLE_SESSION_NAME));
    }

    /** Unicode's L and N categories. */
    private static boolean isLetterOrNumber(int cp) {
        return switch (Character.getType(cp)) {
            case Character.UPPERCASE_LETTER,
                    Character.LOWERCASE_LETTER,
                    Character.TITLECASE_LETTER,
                    Character.MODIFIER_LETTER,
                    Character.OTHER_LETTER,
                    Character.DECIMAL_DIGIT_NUMBER,
                    Character.LETTER_NUMBER,
                    Character.OTHER_NUMBER -> true;
            default -> false;
        };
    }

    /** Unicode's Z category. */
    private static boolean isSeparator(int cp) {
        return switch (Character.getType(cp)) {
            case Character.SPACE_SEPARATOR, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> true;
            default -> false;
        };
    }
}
