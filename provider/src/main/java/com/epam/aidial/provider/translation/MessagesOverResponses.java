package com.epam.aidial.provider.translation;

import com.anthropic.errors.AnthropicInvalidDataException;
import com.anthropic.models.beta.messages.MessageCreateParams;
import com.epam.aidial.provider.anthropic.AnthropicErrors;
import com.epam.aidial.provider.api.AdapterRequest;
import com.epam.aidial.provider.api.AdapterResponse;
import com.epam.aidial.provider.api.Body;
import com.epam.aidial.provider.api.CacheHeaders;
import com.epam.aidial.provider.api.CacheHeaders.MessagesCacheHeaders;
import com.epam.aidial.provider.api.Handler;
import com.epam.aidial.provider.api.InvalidRequestException;
import com.epam.aidial.provider.core.Answers;
import com.epam.aidial.provider.core.Bodies;
import com.epam.aidial.provider.core.Buffers;
import com.epam.aidial.provider.json.Json;
import com.epam.aidial.provider.upstream.AdapterErrors;
import com.epam.aidial.provider.upstream.ClientGoneException;
import com.epam.aidial.provider.upstream.UpstreamClient;
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
 * the {@link Handler}, and what comes back is translated into the answer of the Messages API. Everything the
 * client sees is Anthropic-shaped, errors included.
 *
 * <p>A request that cannot be translated is a 400; an answer of the upstream that is not what the Responses API says
 * is a 500 whose detail is logged, never sent.
 */
public final class MessagesOverResponses implements Handler {

    private static final Logger log = LoggerFactory.getLogger(MessagesOverResponses.class);

    private static final String MESSAGES_PATH = "/anthropic/v1/messages";
    private static final String RESPONSES_PATH = "/openai/v1/responses";

    /** DIAL Core names the deployment the client called: the model of the message, whatever model the request says. */
    static final String DEPLOYMENT_HEADER = "X-DIAL-DEPLOYMENT-ID";

    private final Handler upstream;

    /**
     * Whether the upstream is DIAL Core, which routes the Responses API by the model: the call then names the
     * deployment, where an upstream of its own gets the model of the client's request.
     */
    private final boolean callsCore;

    /** Served by an upstream of its own: it gets the model of the client's request. */
    public static MessagesOverResponses withOwnUpstream(Handler upstream) {
        return new MessagesOverResponses(upstream, false);
    }

    /**
     * Served through DIAL Core, which routes the Responses API by the model: the call names the deployment. {@code
     * upstream} is {@link CoreHandler#handle}.
     */
    public static MessagesOverResponses withCoreUpstream(Handler upstream) {
        return new MessagesOverResponses(upstream, true);
    }

    private MessagesOverResponses(Handler upstream, boolean callsCore) {
        this.upstream = upstream;
        this.callsCore = callsCore;
    }

    @Override
    public Future<AdapterResponse> handle(AdapterRequest request) {
        if (request.method() != HttpMethod.POST || !request.path().equals(MESSAGES_PATH)) {
            // count_tokens: the Responses API has nothing to count with
            return Future.succeededFuture(AnthropicErrors.error(404, "not_found_error", "Endpoint not supported"));
        }
        ToolNames names = new ToolNames();
        RequestTranslator.Translation translated;
        boolean streaming;
        try {
            MessageCreateParams.Body body = AnthropicJson.parseRequest(Buffers.array(request.body()));
            boolean stream = AnthropicJson.stream(body);
            translated = RequestTranslator.translate(body, names);
            streaming = stream;
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
        if (callsCore) {
            translated.request().model(deployment);
        }
        AdapterRequest call = upstreamRequest(
                request, ResponsesJson.requestJson(translated.request(), streaming), translated.cacheHeaders());
        return Answers.call(upstream, call)
                .compose(answer -> streaming && UpstreamClient.UpstreamResponse.isSuccess(answer.status())
                        ? Future.succeededFuture(answer)
                        : Bodies.whole(answer))
                .map(answer -> streaming
                        ? stream(answer, deployment, names, translated.cacheHeaders())
                        : answer(answer, deployment, names, translated.cacheHeaders()))
                .recover(ClientGoneException.unlessGone(request.aborted(), MessagesOverResponses::failed));
    }

    private static Future<AdapterResponse> failed(Throwable e) {
        log.error("The Responses API call failed", e);
        return Future.succeededFuture(internalError());
    }

    private AdapterResponse answer(
            AdapterResponse answer, String deployment, ToolNames names, MessagesCacheHeaders cacheHeaders) {
        try {
            byte[] body = bytes(answer);
            if (answer.status() >= 400) {
                return relayedError(answer, body);
            }
            Response response = ResponsesJson.parseResponse(body);
            AdapterResponse message = AdapterResponse.json(
                    200, AnthropicJson.write(ResponseTranslator.message(response, deployment, names)));
            copyHeaders(answer.headers(), message.headers());
            cacheHeaders.toClient(answer.headers(), message.headers());
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

    private AdapterResponse stream(
            AdapterResponse answer, String deployment, ToolNames names, MessagesCacheHeaders cacheHeaders) {
        if (answer.status() >= 400) {
            return relayedError(answer, bytes(answer));
        }
        TranslatedStream translated = new TranslatedStream(new StreamTranslator(deployment, names));
        switch (answer.body()) {
            case Body.Stream stream ->
                stream.pipe().endOnFailure(false).to(translated.sink()).onFailure(translated::fail);
            case Body.Bytes whole -> {
                translated.sink().write(whole.buffer());
                translated.sink().end();
            }
        }
        AdapterResponse message = new AdapterResponse(
                answer.status(),
                MultiMap.caseInsensitiveMultiMap().set("Content-Type", "text/event-stream; charset=utf-8"),
                new Body.Stream(translated.output().pipe()));
        copyHeaders(answer.headers(), message.headers());
        cacheHeaders.toClient(answer.headers(), message.headers());
        return message;
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
    private static AdapterRequest upstreamRequest(
            AdapterRequest request, String json, MessagesCacheHeaders cacheHeaders) {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap().addAll(request.headers());
        // describe the body of the Messages request, not the translated one; the body is read whole, so plain bytes
        headers.remove("Content-Length").remove("Accept-Encoding").set("Content-Type", "application/json");
        // The path is in the Messages body's coordinates: in the Responses body's it is another one, or none
        cacheHeaders.toUpstream(headers);
        return new AdapterRequest(
                request.method(),
                RESPONSES_PATH,
                request.query(),
                headers,
                Buffer.buffer(json),
                request.aborted(),
                request.core());
    }

    /** Those of the upstream that describe its body, or the cache of its coordinates, are not the client's. */
    private static void copyHeaders(MultiMap from, MultiMap to) {
        MultiMap forwarded = MultiMap.caseInsensitiveMultiMap();
        from.forEach(header -> {
            String name = header.getKey().toLowerCase(Locale.ROOT);
            if (!name.startsWith("content-") && !CacheHeaders.isCacheHeader(name)) {
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
