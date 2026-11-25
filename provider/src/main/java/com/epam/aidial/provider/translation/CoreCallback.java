package com.epam.aidial.provider.translation;

import com.epam.aidial.provider.api.AdapterResponse;
import com.epam.aidial.provider.json.Json;
import io.vertx.core.MultiMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * What the Responses call back to DIAL Core is made of, however it is sent (over HTTP, or through the host of a
 * plugin): of the headers only those DIAL Core reads go: the key, the cache policy and the upstream pin.
 */
final class CoreCallback {

    private static final Set<String> FORWARDED_HEADERS = Set.of("api-key", "x-dial-cache-policy", "x-upstream-id");

    private CoreCallback() {}

    static MultiMap headers(MultiMap request) {
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

    /** Null if there is no URL; a blank one is none. */
    static @Nullable String url(@Nullable String dialUrl) {
        return dialUrl == null || dialUrl.isBlank() || dialUrl.equals("/") ? null : dialUrl;
    }
}
