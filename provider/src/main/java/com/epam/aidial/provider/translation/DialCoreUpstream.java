package com.epam.aidial.provider.translation;

import com.epam.aidial.provider.api.AdapterRequest;
import com.epam.aidial.provider.api.AdapterResponse;
import com.epam.aidial.provider.api.Body;
import com.epam.aidial.provider.core.Buffers;
import com.epam.aidial.provider.json.Json;
import com.epam.aidial.provider.upstream.ClientGoneException;
import com.epam.aidial.provider.upstream.UpstreamClient;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Responses call of the translator when it runs as a service of DIAL Core (as DIAL Core's translator contract has
 * it): it calls DIAL Core back, {@code POST <DIAL_URL>openai/v1/responses}, with the caller's key. DIAL Core routes the
 * Responses API by the model, so the {@code model} of the body is the deployment's name. Of the request's headers only
 * the key, the cache policy and the upstream pin go on: DIAL Core reads those.
 */
final class DialCoreUpstream implements ResponsesUpstream {

    private static final Logger log = LoggerFactory.getLogger(DialCoreUpstream.class);

    private static final Set<String> FORWARDED_HEADERS = Set.of("api-key", "x-dial-cache-policy", "x-upstream-id");

    private final UpstreamClient client;
    private final @Nullable String dialUrl;

    /**
     * @param dialUrl DIAL Core's URL, ending in a slash; null if DIAL_URL is not set
     */
    DialCoreUpstream(UpstreamClient client, @Nullable String dialUrl) {
        this.client = client;
        this.dialUrl = dialUrl == null || dialUrl.isBlank() || dialUrl.equals("/") ? null : dialUrl;
    }

    @Override
    public Future<AdapterResponse> create(AdapterRequest request) {
        if (dialUrl == null) {
            log.error("DIAL_URL is not set");
            return Future.succeededFuture(error(500, "Internal server error"));
        }
        String deployment = request.headers().get(MessagesOverResponses.DEPLOYMENT_HEADER);
        Buffer body = Buffers.wrap(withModel(Buffers.array(request.body()), deployment));
        boolean stream = Boolean.TRUE.equals(streamFlag(body));
        return client.send(
                        HttpMethod.POST,
                        dialUrl + "openai/v1/responses",
                        headers(request.headers()),
                        body,
                        request.aborted())
                .compose(response -> answer(response, stream))
                .recover(e -> {
                    if (e instanceof ClientGoneException) {
                        return Future.failedFuture(e);
                    }
                    log.error("Failed to reach DIAL Core: {}", e.toString());
                    return Future.succeededFuture(error(502, "Failed to reach DIAL Core"));
                });
    }

    private static Future<AdapterResponse> answer(UpstreamClient.UpstreamResponse response, boolean stream) {
        if (stream && response.isSuccess()) {
            return Future.succeededFuture(new AdapterResponse(response.status(), response.headers(), response.relay()));
        }
        return response.body()
                .map(body -> new AdapterResponse(response.status(), response.headers(), new Body.Bytes(body)));
    }

    /** The body as the translator wrote it, with the deployment as the model. */
    private static byte[] withModel(byte[] translated, @Nullable String deployment) {
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) Json.parse(translated);
        Map<String, Object> withModel = new LinkedHashMap<>(body);
        if (deployment != null) {
            withModel.put("model", deployment);
        }
        return Json.encode(withModel).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static @Nullable Boolean streamFlag(Buffer body) {
        return Json.parse(Buffers.array(body)) instanceof Map<?, ?> map && map.get("stream") instanceof Boolean flag
                ? flag
                : null;
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
    private static AdapterResponse error(int status, String message) {
        return AdapterResponse.json(status, Json.encode(Map.of("error", Map.of("message", message))));
    }
}
