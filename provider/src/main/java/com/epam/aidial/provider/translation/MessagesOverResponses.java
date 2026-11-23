package com.epam.aidial.provider.translation;

import com.anthropic.errors.AnthropicInvalidDataException;
import com.anthropic.models.beta.messages.MessageCreateParams;
import com.epam.aidial.provider.anthropic.AnthropicErrors;
import com.epam.aidial.provider.api.AdapterRequest;
import com.epam.aidial.provider.api.AdapterResponse;
import com.epam.aidial.provider.api.Body;
import com.epam.aidial.provider.api.InvalidRequestException;
import com.epam.aidial.provider.api.ModelProvider;
import com.epam.aidial.provider.core.Answers;
import com.epam.aidial.provider.core.Buffers;
import com.epam.aidial.provider.json.Json;
import com.epam.aidial.provider.upstream.AdapterErrors;
import com.openai.errors.OpenAIInvalidDataException;
import com.openai.models.responses.Response;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Anthropic Messages API of a model that only speaks the OpenAI Responses API: the request is translated, sent to
 * the {@link ResponsesUpstream}, and what comes back is translated into the answer of the Messages API. Everything the
 * client sees is Anthropic-shaped, errors included.
 *
 * <p>A request that cannot be translated is a 400; an answer of the upstream that is not what the Responses API says
 * is a 500 whose detail is logged, never sent.
 */
public final class MessagesOverResponses implements ModelProvider {

    private static final Logger log = LoggerFactory.getLogger(MessagesOverResponses.class);

    private static final String MESSAGES_PATH = "/anthropic/v1/messages";
    private static final String RESPONSES_PATH = "/openai/v1/responses";

    /** DIAL Core names the deployment the client called: the model of the message, whatever model the request says. */
    static final String DEPLOYMENT_HEADER = "X-DIAL-DEPLOYMENT-ID";

    private final ResponsesUpstream upstream;

    public MessagesOverResponses(ResponsesUpstream upstream) {
        this.upstream = upstream;
    }

    @Override
    public Future<AdapterResponse> handle(AdapterRequest request) {
        if (!request.path().equals(MESSAGES_PATH)) {
            // count_tokens: the Responses API has nothing to count with
            return Future.succeededFuture(AnthropicErrors.error(404, "not_found_error", "Endpoint not supported"));
        }
        Map<String, Object> translated;
        try {
            MessageCreateParams.Body body = AnthropicJson.parseRequest(Buffers.array(request.body()));
            boolean stream = AnthropicJson.stream(body);
            if (stream) {
                return Future.succeededFuture(
                        AnthropicErrors.error(501, "api_error", "Streaming is not supported yet"));
            }
            translated = RequestTranslator.translate(body, stream);
        } catch (InvalidRequestException e) {
            return Future.succeededFuture(invalidRequest(e.getMessage()));
        } catch (AnthropicInvalidDataException e) {
            return Future.succeededFuture(invalidRequest(e.getMessage()));
        }
        String deployment = request.headers().get(DEPLOYMENT_HEADER);
        if (deployment == null || deployment.isEmpty()) {
            // DIAL Core always sends it: a bug of the caller, not of the client's request
            log.error("The request has no {} header", DEPLOYMENT_HEADER);
            return Future.succeededFuture(internalError());
        }
        AdapterRequest call = upstreamRequest(request, Json.encode(translated));
        return Answers.call(upstream::create, call)
                .map(answer -> answer(answer, deployment))
                .recover(e -> request.aborted().isComplete() ? Future.failedFuture(e) : failed(e));
    }

    private static Future<AdapterResponse> failed(Throwable e) {
        log.error("The Responses API call failed", e);
        return Future.succeededFuture(internalError());
    }

    private AdapterResponse answer(AdapterResponse answer, String deployment) {
        try {
            byte[] body = bytes(answer);
            if (answer.status() >= 400) {
                return relayedError(answer, body);
            }
            Response response = ResponsesJson.parseResponse(body);
            AdapterResponse message =
                    AdapterResponse.json(200, Json.encode(ResponseTranslator.message(response, deployment)));
            copyHeaders(answer.headers(), message.headers());
            return message;
        } catch (ResponseTranslator.ResponseFailedException e) {
            return AnthropicErrors.error(e.status(), ErrorCodes.anthropicType(e.status()), e.getMessage());
        } catch (UpstreamResponseException | OpenAIInvalidDataException e) {
            log.error("The Responses API answered what is not a Responses API response: {}", e.getMessage());
            return internalError();
        } catch (RuntimeException e) {
            log.error("The Responses API answer could not be translated", e);
            return internalError();
        }
    }

    /** The upstream's failure with the same status, its message and its headers (a {@code Retry-After}, say). */
    private static AdapterResponse relayedError(AdapterResponse answer, byte[] body) {
        String text = new String(body, StandardCharsets.UTF_8);
        AdapterResponse error = AnthropicErrors.error(
                answer.status(), ErrorCodes.anthropicType(answer.status()), errorMessage(text, answer.status()));
        copyHeaders(answer.headers(), error.headers());
        return error;
    }

    /** The {@code error.message} of an OpenAI-shaped body, else the body's text, else a default. */
    private static String errorMessage(String body, int status) {
        try {
            if (Json.parse(body) instanceof Map<?, ?> json
                    && json.get("error") instanceof Map<?, ?> error
                    && error.get("message") instanceof String message) {
                return message;
            }
        } catch (Json.ParseException e) {
            // not JSON: the text is the message
        }
        return body.isEmpty() ? "The Responses API handler answered " + status : body;
    }

    private static byte[] bytes(AdapterResponse answer) {
        if (answer.body() instanceof Body.Bytes bytes) {
            return Buffers.array(bytes.buffer());
        }
        // a whole answer was asked for
        throw new IllegalStateException("The Responses API answered a stream for a request that is not streamed");
    }

    /** The Messages request's headers go on: the {@code X-Upstream-*} ones select the upstream. */
    private static AdapterRequest upstreamRequest(AdapterRequest request, String json) {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap().addAll(request.headers());
        // describe the body of the Messages request, not the translated one; the body is read whole, so plain bytes
        headers.remove("Content-Length").remove("Accept-Encoding").set("Content-Type", "application/json");
        // The path is in the Messages body's coordinates, which the Responses route cannot stamp (see the cache
        // headers)
        headers.remove(AdapterResponse.CACHE_BREAKPOINT_HEADER);
        return new AdapterRequest(
                HttpMethod.POST, RESPONSES_PATH, null, headers, Buffer.buffer(json), request.aborted());
    }

    /** Those of the upstream that describe its body, or the cache of its coordinates, are not the client's. */
    private static void copyHeaders(MultiMap from, MultiMap to) {
        MultiMap forwarded = MultiMap.caseInsensitiveMultiMap();
        from.forEach(header -> {
            String name = header.getKey().toLowerCase(Locale.ROOT);
            if (!name.startsWith("content-") && !name.startsWith("x-dial-cache-")) {
                forwarded.add(header.getKey(), header.getValue());
            }
        });
        Answers.copyForwarded(forwarded, to);
    }

    private static AdapterResponse invalidRequest(String message) {
        return AnthropicErrors.error(400, "invalid_request_error", message);
    }

    private static AdapterResponse internalError() {
        return AnthropicErrors.error(500, "api_error", AdapterErrors.INTERNAL_ERROR);
    }
}
