package com.epam.aidial.provider.translation;

import com.epam.aidial.provider.api.AdapterRequest;
import com.epam.aidial.provider.api.AdapterResponse;
import com.epam.aidial.provider.api.Handler;
import com.epam.aidial.provider.upstream.ClientGoneException;
import io.vertx.core.Future;
import io.vertx.core.http.HttpMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Responses call of the translator when it runs as a plugin of DIAL Core: DIAL Core is called in its own process,
 * through the host of the request ({@link AdapterRequest#core()}), with no HTTP call and no key to look up; the call is
 * served with the credentials of the request. What is called is the same as over HTTP (see {@link CoreCallback}).
 */
final class HostUpstream implements Handler {

    private static final Logger log = LoggerFactory.getLogger(HostUpstream.class);

    @Override
    public Future<AdapterResponse> handle(AdapterRequest request) {
        Handler core = request.core();
        if (core == null) {
            throw new IllegalStateException("no host to call DIAL Core through");
        }
        AdapterRequest call = new AdapterRequest(
                HttpMethod.POST,
                request.path(),
                null,
                CoreCallback.headers(request.headers()),
                request.body(),
                request.aborted());
        return core.handle(call).recover(ClientGoneException.unlessGone(request.aborted(), e -> {
            log.error("Failed to call DIAL Core: {}", e.toString());
            return Future.succeededFuture(CoreCallback.error(502, "Failed to call DIAL Core"));
        }));
    }
}
