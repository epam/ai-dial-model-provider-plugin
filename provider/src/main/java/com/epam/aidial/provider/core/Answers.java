package com.epam.aidial.provider.core;

import com.epam.aidial.provider.api.AdapterRequest;
import com.epam.aidial.provider.api.AdapterResponse;
import com.epam.aidial.provider.api.ModelProvider;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Answers {

    private static final Logger log = LoggerFactory.getLogger(Answers.class);

    /** Hop-by-hop (RFC 9110 7.6.1), and the framing headers the server writes itself. */
    private static final Set<String> NOT_FORWARDED = Set.of(
            "connection",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade",
            "content-length",
            "server",
            "date");

    private Answers() {}

    /** A provider that throws yields a failed future, so that every failure is handled alike. */
    public static Future<AdapterResponse> call(ModelProvider provider, AdapterRequest request) {
        try {
            return provider.handle(request);
        } catch (RuntimeException | StackOverflowError e) {
            // a request nested past every depth limit still gets its 500
            return Future.failedFuture(e);
        }
    }

    /** Takes the path and not the request, which holds its body (see Unsent). */
    public static AdapterResponse internalError(String path, Throwable cause) {
        log.error("request failed: path={}", path, cause);
        return AdapterResponse.internalServerError();
    }

    public static void copyForwarded(MultiMap from, MultiMap to) {
        for (Map.Entry<String, String> header : from) {
            if (!NOT_FORWARDED.contains(header.getKey().toLowerCase(Locale.ROOT))) {
                to.add(header.getKey(), header.getValue());
            }
        }
    }
}
