package com.epam.aidial.provider.translation;

import com.epam.aidial.provider.api.AdapterRequest;
import com.epam.aidial.provider.api.AdapterResponse;
import com.epam.aidial.provider.json.Json;
import com.epam.aidial.provider.upstream.ClientGoneException;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Responses call back to DIAL Core, through {@link AdapterRequest#core()}: over HTTP, or through the host of a
 * plugin. What the call is made of is the same either way: of the headers only those DIAL Core reads go: the key, the
 * cache policy and the upstream pin.
 */
public final class CoreHandler {

    private static final Logger log = LoggerFactory.getLogger(CoreHandler.class);

    private static final Set<String> FORWARDED_HEADERS = Set.of("api-key", "x-dial-cache-policy", "x-upstream-id");

    private CoreHandler() {}

    /** The upstream of the translator that is served through DIAL Core. */
    public static Future<AdapterResponse> handle(AdapterRequest request) {
        return request.core()
                .handle(request.withHeaders(headers(request.headers())))
                .recover(ClientGoneException.unlessGone(request.aborted(), e -> {
                    log.error("Failed to reach DIAL Core: {}", e.toString());
                    return Future.succeededFuture(error(502, "Failed to reach DIAL Core"));
                }));
    }

    private static MultiMap headers(MultiMap request) {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap().set("Content-Type", "application/json");
        request.forEach(header -> {
            if (FORWARDED_HEADERS.contains(header.getKey().toLowerCase(Locale.ROOT))) {
                headers.add(header.getKey(), header.getValue());
            }
        });
        return headers;
    }

    /** The shape of an error of the Responses API: the translator reads the message from it. */
    static AdapterResponse error(int status, String message) {
        return AdapterResponse.json(status, Json.encode(Map.of("error", Map.of("message", message))));
    }
}
