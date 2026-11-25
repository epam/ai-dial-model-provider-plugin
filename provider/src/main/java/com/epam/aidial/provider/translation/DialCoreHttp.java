package com.epam.aidial.provider.translation;

import com.epam.aidial.provider.api.AdapterRequest;
import com.epam.aidial.provider.api.AdapterResponse;
import com.epam.aidial.provider.api.Handler;
import com.epam.aidial.provider.upstream.UpstreamClient;
import io.vertx.core.Future;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link AdapterRequest#core() core} of a request that came over HTTP, to the translator that runs as a service of
 * DIAL Core (as DIAL Core's translator contract has it): it calls DIAL Core back, {@code <DIAL_URL><path>}, with
 * the caller's key (see {@link CoreHandler}).
 */
public final class DialCoreHttp implements Handler {

    private static final Logger log = LoggerFactory.getLogger(DialCoreHttp.class);

    private final UpstreamClient client;
    private final @Nullable String dialUrl;

    /**
     * @param dialUrl DIAL Core's URL, ending in a slash; null if DIAL_URL is not set
     */
    public DialCoreHttp(UpstreamClient client, @Nullable String dialUrl) {
        this.client = client;
        this.dialUrl = url(dialUrl);
    }

    /** Null if there is no URL; a blank one is none. */
    private static @Nullable String url(@Nullable String dialUrl) {
        return dialUrl == null || dialUrl.isBlank() || dialUrl.equals("/") ? null : dialUrl;
    }

    @Override
    public Future<AdapterResponse> handle(AdapterRequest request) {
        if (dialUrl == null) {
            log.error("DIAL_URL is not set");
            return Future.succeededFuture(CoreHandler.error(500, "Internal server error"));
        }
        String query = request.query() == null ? "" : "?" + request.query();
        return client.send(
                        request.method(),
                        dialUrl + request.path().substring(1) + query,
                        request.headers(),
                        request.body(),
                        request.aborted())
                .map(response -> new AdapterResponse(response.status(), response.headers(), response.relay()));
    }
}
