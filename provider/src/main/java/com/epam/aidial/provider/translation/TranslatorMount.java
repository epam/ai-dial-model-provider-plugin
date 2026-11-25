package com.epam.aidial.provider.translation;

import com.epam.aidial.provider.anthropic.AnthropicErrors;
import com.epam.aidial.provider.api.AdapterRequest;
import com.epam.aidial.provider.api.AdapterResponse;
import com.epam.aidial.provider.api.Handler;
import io.vertx.core.Future;
import io.vertx.core.http.HttpMethod;

/**
 * What DIAL Core's translator contract calls: the Messages API of {@link MessagesOverResponses} at the path DIAL Core
 * forwards it to, {@code POST /anthropic/v1/messages}, and nothing else; any other request is a 404 in the Anthropic
 * API's error body.
 */
public final class TranslatorMount implements Handler {

    private static final String MESSAGES_PATH = "/anthropic/v1/messages";

    private final MessagesOverResponses messages;

    public TranslatorMount(MessagesOverResponses messages) {
        this.messages = messages;
    }

    @Override
    public Future<AdapterResponse> handle(AdapterRequest request) {
        if (request.method() == HttpMethod.POST && request.path().equals(MESSAGES_PATH)) {
            return messages.handle(request);
        }
        return Future.succeededFuture(
                AnthropicErrors.error(404, "not_found_error", "Unknown endpoint: " + request.path()));
    }
}
