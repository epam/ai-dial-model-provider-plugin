package com.epam.aidial.provider.translation;

import com.epam.aidial.provider.api.AdapterRequest;

/** The API of the upstream a request names (X-Upstream-Endpoint). */
public enum UpstreamApi {
    /** The Anthropic Messages API: the endpoint has {@code /v1/messages} in it. */
    MESSAGES,
    /** The OpenAI Responses API: the endpoint ends in {@code /responses}. */
    RESPONSES;

    private static final String MESSAGES_PATH = "/v1/messages";
    private static final String RESPONSES_SUFFIX = "/responses";

    /**
     * What the endpoint says. An endpoint that says neither, or none, is {@link #MESSAGES}: the Messages route is the
     * one to refuse it, as it always has.
     */
    public static UpstreamApi of(AdapterRequest request) {
        String endpoint = request.endpoint();
        boolean responses =
                endpoint != null && endpoint.endsWith(RESPONSES_SUFFIX) && !endpoint.contains(MESSAGES_PATH);
        return responses ? RESPONSES : MESSAGES;
    }
}
