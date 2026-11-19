package com.epam.aidial.provider.api;

import io.vertx.core.Future;

/** Nothing below it sees an HTTP server request or response. */
public interface ModelProvider {

    /**
     * The future fails only for a bug: every expected failure is a response. A streamed body is returned paused (see
     * {@link Body.Stream}).
     */
    Future<AdapterResponse> handle(AdapterRequest request);
}
