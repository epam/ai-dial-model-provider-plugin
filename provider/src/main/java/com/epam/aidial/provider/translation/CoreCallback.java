package com.epam.aidial.provider.translation;

import com.epam.aidial.provider.api.AdapterRequest;
import com.epam.aidial.provider.api.AdapterResponse;
import com.epam.aidial.provider.core.Buffers;
import com.epam.aidial.provider.json.Json;
import com.openai.models.responses.ResponseCreateParams;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * What the Responses call back to DIAL Core is made of, however it is sent (over HTTP, or through the host of a
 * plugin): DIAL Core routes the Responses API by the model, so the {@code model} of the body is the deployment's name,
 * and of the headers only those DIAL Core reads go: the key, the cache policy and the upstream pin.
 */
final class CoreCallback {

    private static final Set<String> FORWARDED_HEADERS = Set.of("api-key", "x-dial-cache-policy", "x-upstream-id");

    private CoreCallback() {}

    static Buffer body(AdapterRequest request) {
        ResponseCreateParams.Body translated = ResponsesJson.parseRequest(Buffers.array(request.body()));
        String deployment = request.headers().get(MessagesOverResponses.DEPLOYMENT_HEADER);
        ResponseCreateParams.Body body = deployment == null
                ? translated
                : translated.toBuilder().model(deployment).build();
        return Buffer.buffer(ResponsesJson.write(body).getBytes(StandardCharsets.UTF_8));
    }

    static boolean streams(Buffer body) {
        return ResponsesJson.streams(ResponsesJson.parseRequest(Buffers.array(body)));
    }

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
