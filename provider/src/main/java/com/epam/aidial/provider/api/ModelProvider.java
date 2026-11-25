package com.epam.aidial.provider.api;

import io.vertx.core.Future;

/**
 * Anything that answers a request: a provider, a router, the upstream a translator calls, DIAL Core. Nothing below it
 * sees an HTTP server request or response.
 */
@FunctionalInterface
public interface ModelProvider {

    /**
     * An expected failure is a response. The future fails for a bug, and for a call that was cut off (the caller went
     * away) or could not be made. A streamed body is returned paused (see {@link Body.Stream}).
     */
    Future<AdapterResponse> handle(AdapterRequest request);
}
