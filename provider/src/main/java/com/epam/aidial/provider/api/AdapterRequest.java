package com.epam.aidial.provider.api;

import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * @param path    below the provider's mount point, starting with {@code /}
 * @param query   the raw query string, without {@code ?}; {@code null} when the request has none
 * @param aborted completes when the caller goes away before the response has ended; a provider cancels the
 *                upstream call it made for the request
 * @param core    DIAL Core's create operations, for the translator that calls DIAL Core back: in the process of DIAL
 *                Core with the credentials of the request being served for a plugin (the plugin API's host), over
 *                HTTP for a service
 */
public record AdapterRequest(
        HttpMethod method,
        String path,
        String query,
        MultiMap headers,
        Buffer body,
        Future<Void> aborted,
        Handler core) {

    public static final String ENDPOINT_HEADER = "X-Upstream-Endpoint";

    public static final String KEY_HEADER = "X-Upstream-Key";

    /** A JSON object; a provider binds it. */
    public static final String EXTRA_DATA_HEADER = "X-Upstream-Extra-Data";

    public @Nullable String endpoint() {
        return headers.get(ENDPOINT_HEADER);
    }

    /** Empty when DIAL Core sent an empty key, null when it sent none. */
    public @Nullable String key() {
        return headers.get(KEY_HEADER);
    }

    /**
     * The last {@code api-version} of the query: the versioned Azure API needs one.
     *
     * @throws InvalidRequestException when the query has none, or its last one is empty
     */
    public String requiredApiVersion() {
        String version = queryParams().get("api-version");
        if (version == null || version.isEmpty()) {
            throw new InvalidRequestException("api-version is a required query parameter");
        }
        return version;
    }

    /**
     * A name that repeats keeps its first position and its last value, {@code +} is a space, a pair without {@code =}
     * has an empty value and an empty segment is skipped. A segment whose percent escapes are malformed is kept
     * undecoded.
     */
    public Map<String, String> queryParams() {
        if (query == null) {
            return Map.of();
        }
        Map<String, String> params = new LinkedHashMap<>();
        for (String segment : query.split("&")) {
            if (segment.isEmpty()) {
                continue;
            }
            int eq = segment.indexOf('=');
            params.put(
                    decode(eq < 0 ? segment : segment.substring(0, eq)),
                    eq < 0 ? "" : decode(segment.substring(eq + 1)));
        }
        return params;
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return s;
        }
    }

    public AdapterRequest withHeaders(MultiMap newHeaders) {
        return new AdapterRequest(method, path, query, newHeaders, body, aborted, core);
    }

    public AdapterRequest withPath(String newPath) {
        return new AdapterRequest(method, newPath, query, headers, body, aborted, core);
    }

    /** For the functions that outlive the upstream send (see {@code Unsent}): a captured request keeps its body. */
    public AdapterRequest withoutBody() {
        return new AdapterRequest(method, path, query, headers, Buffer.buffer(), aborted, core);
    }
}
