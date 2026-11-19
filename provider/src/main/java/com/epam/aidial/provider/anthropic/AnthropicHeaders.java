package com.epam.aidial.provider.anthropic;

import io.vertx.core.MultiMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The authentication headers are added later, by {@link UpstreamAuth}. */
final class AnthropicHeaders {

    private static final String ANTHROPIC_BETA = "Anthropic-Beta";
    private static final String ANTHROPIC_VERSION = "Anthropic-Version";
    private static final String DEFAULT_VERSION = "2023-06-01"; // the SDK's default anthropic-version header

    private static final List<String> ERROR_HEADERS =
            List.of("Retry-After", "Retry-After-Ms", "Content-Type", "Content-Encoding");

    private AnthropicHeaders() {}

    /** accept-encoding is forwarded: the response is relayed undecoded, so its content-encoding stays true. */
    static MultiMap toUpstream(MultiMap client, Target target) {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        for (Map.Entry<String, String> h : client) {
            String name = h.getKey().toLowerCase(Locale.ROOT);
            // The event stream is decoded here, so it must come uncompressed: no accept-encoding is forwarded.
            boolean acceptEncoding = name.equals("accept-encoding") && !target.invokeApi();
            if (name.startsWith("anthropic-") || acceptEncoding) {
                headers.add(h.getKey(), h.getValue());
            }
        }
        dropUnsupportedBetas(headers, target.cloud());
        setVersion(headers, client, target);
        headers.set("Content-Type", "application/json");
        headers.set("Accept", "application/json");
        return headers;
    }

    private static void dropUnsupportedBetas(MultiMap headers, Cloud cloud) {
        List<String> sent = headers.getAll(ANTHROPIC_BETA);
        if (sent.isEmpty() || cloud.unsupportedBetas.isEmpty()) {
            return;
        }
        String kept = Stripping.dropBetas(String.join(",", sent), cloud.unsupportedBetas);
        if (kept.isEmpty()) {
            headers.remove(ANTHROPIC_BETA);
        } else {
            headers.set(ANTHROPIC_BETA, kept);
        }
    }

    /** A client's own version wins, an empty one too; there is no default when the version goes into the body. */
    private static void setVersion(MultiMap headers, MultiMap client, Target target) {
        if (!client.contains(ANTHROPIC_VERSION) && !target.versionInBody()) {
            headers.set(ANTHROPIC_VERSION, DEFAULT_VERSION);
        }
    }

    static MultiMap errorToClient(MultiMap upstream) {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        for (String name : ERROR_HEADERS) {
            upstream.getAll(name).forEach(value -> headers.add(name, value));
        }
        return headers;
    }

    static MultiMap successToClient(MultiMap upstream) {
        return MultiMap.caseInsensitiveMultiMap().addAll(upstream);
    }

    static MultiMap eventStreamToClient(MultiMap upstream) {
        return successToClient(upstream)
                .set("Content-Type", "text/event-stream")
                .remove("Content-Encoding");
    }
}
