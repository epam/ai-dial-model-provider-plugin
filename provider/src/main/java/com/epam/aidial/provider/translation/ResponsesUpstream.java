package com.epam.aidial.provider.translation;

import com.epam.aidial.provider.api.AdapterRequest;
import com.epam.aidial.provider.api.AdapterResponse;
import io.vertx.core.Future;

/**
 * Where the translated request goes: the one thing that tells the modes of the translator apart. The answer is what
 * the Responses API route, or DIAL Core, answers.
 */
@FunctionalInterface
public interface ResponsesUpstream {

    /**
     * @param request a Responses API create request: path {@code /openai/v1/responses}, a JSON body, and the headers of
     *     the Messages request it was translated from
     */
    Future<AdapterResponse> create(AdapterRequest request);
}
