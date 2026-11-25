package com.epam.aidial.provider.translation;

import com.epam.aidial.provider.api.AdapterRequest;
import com.epam.aidial.provider.api.AdapterResponse;
import com.epam.aidial.provider.api.Body;
import com.epam.aidial.provider.api.ModelProvider;
import com.epam.aidial.provider.upstream.ClientGoneException;
import com.epam.aidial.provider.upstream.UpstreamClient;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Responses call of the translator when it runs as a service of DIAL Core (as DIAL Core's translator contract has
 * it): it calls DIAL Core back, {@code POST <DIAL_URL>openai/v1/responses}, with the caller's key (see {@link
 * CoreCallback}).
 */
final class DialCoreUpstream implements ModelProvider {

    private static final Logger log = LoggerFactory.getLogger(DialCoreUpstream.class);

    private final UpstreamClient client;
    private final @Nullable String dialUrl;

    /**
     * @param dialUrl DIAL Core's URL, ending in a slash; null if DIAL_URL is not set
     */
    DialCoreUpstream(UpstreamClient client, @Nullable String dialUrl) {
        this.client = client;
        this.dialUrl = CoreCallback.url(dialUrl);
    }

    @Override
    public Future<AdapterResponse> handle(AdapterRequest request) {
        if (dialUrl == null) {
            log.error("DIAL_URL is not set");
            return Future.succeededFuture(CoreCallback.error(500, "Internal server error"));
        }
        Buffer body = CoreCallback.body(request);
        boolean stream = CoreCallback.streams(body);
        return client.send(
                        HttpMethod.POST,
                        dialUrl + "openai/v1/responses",
                        CoreCallback.headers(request.headers()),
                        body,
                        request.aborted())
                .compose(response -> answer(response, stream))
                .recover(e -> {
                    if (e instanceof ClientGoneException) {
                        return Future.failedFuture(e);
                    }
                    log.error("Failed to reach DIAL Core: {}", e.toString());
                    return Future.succeededFuture(CoreCallback.error(502, "Failed to reach DIAL Core"));
                });
    }

    private static Future<AdapterResponse> answer(UpstreamClient.UpstreamResponse response, boolean stream) {
        if (stream && response.isSuccess()) {
            return Future.succeededFuture(new AdapterResponse(response.status(), response.headers(), response.relay()));
        }
        return response.body()
                .map(body -> new AdapterResponse(response.status(), response.headers(), new Body.Bytes(body)));
    }
}
