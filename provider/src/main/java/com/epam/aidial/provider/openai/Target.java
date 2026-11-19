package com.epam.aidial.provider.openai;

import com.epam.aidial.provider.credentials.Secret;
import com.google.common.net.PercentEscaper;
import io.vertx.core.MultiMap;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.StringJoiner;
import org.jspecify.annotations.Nullable;

/**
 * @param baseUrl the API's URL (.../responses), no query
 * @param key     X-Upstream-Key; null for none. An empty key is a key, except on Bedrock.
 */
record Target(
        String baseUrl,
        boolean versioned,
        @Nullable String version,
        MultiMap headers,
        boolean proxiedAuth,
        UpstreamExtraData extra,
        @Nullable Secret key,
        @Nullable Bedrock bedrock) {

    /** What a query name or value keeps besides ASCII letters and digits; a space is a plus, any other byte %XX. */
    private static final PercentEscaper QUERY_ESCAPER = new PercentEscaper("-._~!$'()*,/:?@", true);

    enum BedrockApi {
        MANTLE,
        RUNTIME
    }

    record Bedrock(BedrockApi api, String region) {}

    boolean mantle() {
        return bedrock != null && bedrock.api() == BedrockApi.MANTLE;
    }

    String url(Endpoint endpoint, String id, Map<String, String> clientQuery) {
        String url = withPath(baseUrl, (endpoint.byId() ? "/" + id : "") + endpoint.suffix());
        String query = query(version, clientQuery);
        return query.isEmpty() ? url : url + "?" + query;
    }

    static String query(@Nullable String version, Map<String, String> client) {
        StringJoiner query = new StringJoiner("&");
        if (version != null && !version.isEmpty()) {
            query.add("api-version=" + QUERY_ESCAPER.escape(version));
        }
        client.forEach((name, value) -> {
            if (!name.equals("api-version") && !value.isEmpty()) {
                query.add(QUERY_ESCAPER.escape(name) + "=" + QUERY_ESCAPER.escape(value));
            }
        });
        return query.toString();
    }

    static String withPath(String base, String suffix) {
        URI joined;
        try {
            joined = URI.create(base + new URI(null, null, suffix, null).toASCIIString());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(e);
        }
        String path = joined.normalize().getRawPath();
        // a trailing "." or ".." segment resolves to the directory: the path is not left with a slash that was not
        // there
        if (path.length() > 1 && path.endsWith("/") && !joined.getRawPath().endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        while (path.startsWith("/..")) {
            path = path.substring(3);
        }
        return joined.getScheme() + "://" + joined.getRawAuthority() + path;
    }
}
