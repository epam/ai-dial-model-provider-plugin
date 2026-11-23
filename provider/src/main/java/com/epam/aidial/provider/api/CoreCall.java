package com.epam.aidial.provider.api;

import io.vertx.core.Future;

/**
 * A call to DIAL Core made while serving a request, in the process of DIAL Core and with the credentials of the request
 * it serves: how a plugin reaches another deployment (the plugin API's host). The call is a request to one of DIAL
 * Core's create operations; the answer is any status DIAL Core gives, its body {@link Body.Stream a stream}.
 */
@FunctionalInterface
public interface CoreCall {

    /**
     * @param request the path ({@code /openai/v1/responses}), the headers and the JSON body of the call; its {@code
     *     aborted} stops the call
     * @return the answer; it fails when the call cannot be made
     */
    Future<AdapterResponse> call(AdapterRequest request);
}
