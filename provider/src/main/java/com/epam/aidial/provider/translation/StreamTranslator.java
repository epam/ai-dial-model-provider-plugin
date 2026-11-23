package com.epam.aidial.provider.translation;

import com.epam.aidial.provider.json.Json;
import com.openai.errors.OpenAIInvalidDataException;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseStreamEvent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A stream of Responses API events as a stream of Messages API events: a state machine that is told each event and
 * answers with the SSE text to send. One content block is open at a time; opening one closes the one before. After the
 * message has ended, or has failed, nothing more is said. Pure: no I/O and no Vert.x.
 *
 * <p>A failure of the stream (an event that is not one, an error event, an end without the final event) is an {@code
 * error} event, the response being 200 already.
 */
final class StreamTranslator {

    private static final Logger log = LoggerFactory.getLogger(StreamTranslator.class);

    private final String model;
    private String messageId = java.util.UUID.randomUUID().toString();
    private int nextIndex;
    private boolean terminated;
    private boolean started;
    private final java.util.Set<String> warnedTypes = new java.util.HashSet<>();
    private @Nullable String openKey;
    private int openIndex;

    StreamTranslator(String model) {
        this.model = model;
    }

    /** The upstream is alive, and says it between two events. */
    List<String> onHeartbeat() {
        return terminated ? List.of() : List.of(sse(Map.of("type", "ping")));
    }

    /** The data of one event of the upstream's stream. */
    List<String> onData(String data) {
        if (terminated) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        try {
            ResponseStreamEvent event = ResponsesJson.parseEvent(data.getBytes(StandardCharsets.UTF_8));
            handle(ResponsesJson.requiredType(event._json(), "event"), event, out);
        } catch (UpstreamResponseException | OpenAIInvalidDataException e) {
            log.error("The Responses API stream holds what is not a Responses API event: {}", e.getMessage());
            error(500, "Internal server error", out);
        } catch (RuntimeException e) {
            log.error("A Responses API event could not be translated", e);
            error(500, "Internal server error", out);
        }
        return out;
    }

    /** The upstream's stream ended. */
    List<String> onEnd() {
        List<String> out = new ArrayList<>();
        if (!terminated) {
            log.warn("The Responses API stream ended without a final event");
            error(502, "The Responses API stream ended before the response completed", out);
        }
        return out;
    }

    /** The upstream's stream broke. */
    List<String> onFailure(Throwable cause) {
        List<String> out = new ArrayList<>();
        if (!terminated) {
            log.error("The Responses API stream failed", cause);
            error(500, "Internal server error", out);
        }
        return out;
    }

    private void handle(String type, ResponseStreamEvent event, List<String> out) {
        switch (type) {
            case "response.created" -> {
                messageId = event.created().orElseThrow().response().id();
                start(out);
            }
            case "response.output_text.delta" -> {
                var delta = event.outputTextDelta().orElseThrow();
                textDelta("text:" + delta.outputIndex() + ":" + delta.contentIndex(), delta.delta(), out);
            }
            case "response.refusal.delta" -> {
                var delta = event.refusalDelta().orElseThrow();
                textDelta("text:" + delta.outputIndex() + ":" + delta.contentIndex(), delta.delta(), out);
            }
            case "response.completed" -> finish(event.completed().orElseThrow().response(), out);
            case "response.incomplete" ->
                finish(event.incomplete().orElseThrow().response(), out);
            case "response.failed" -> {
                Response response = event.failed().orElseThrow().response();
                ResponseTranslator.ResponseFailedException failure = ResponseTranslator.failure(response);
                error(failure.status(), failure.getMessage(), out);
            }
            case "error" -> {
                var failure = event.error().orElseThrow();
                error(ErrorCodes.status(failure.code().orElse("")), failure.message(), out);
            }
            default -> dropped(type);
        }
    }

    /**
     * The Responses API says a lot that a message has no use for ({@code response.content_part.added}, {@code
     * response.output_text.done}, ...): that is debug. A type outside the API's {@code response.} namespace, or one
     * that is new, is named once in a warning.
     */
    private void dropped(String type) {
        if (type.startsWith("response.")) {
            log.debug("Dropping a Responses API event: {}", type);
        } else if (warnedTypes.add(type)) {
            log.warn("Dropping an unsupported Responses API event: {}", type);
        }
    }

    private void start(List<String> out) {
        if (started) {
            return;
        }
        started = true;
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("input_tokens", 0);
        usage.put("output_tokens", 0);
        usage.put("cache_creation_input_tokens", 0);
        usage.put("cache_read_input_tokens", 0);
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", messageId);
        message.put("type", "message");
        message.put("role", "assistant");
        message.put("model", model);
        message.put("content", List.of());
        message.put("stop_reason", null);
        message.put("stop_sequence", null);
        message.put("usage", usage);
        out.add(sse(event("message_start", "message", message)));
    }

    private void openBlock(String key, Map<String, Object> block, List<String> out) {
        start(out);
        closeBlock(out);
        openKey = key;
        openIndex = nextIndex++;
        Map<String, Object> event = event("content_block_start", "index", openIndex);
        event.put("content_block", block);
        out.add(sse(event));
    }

    private boolean isOpen(String key) {
        return key.equals(openKey);
    }

    private void delta(Map<String, Object> delta, List<String> out) {
        if (openKey == null) {
            log.warn("Delta event has arrived before open event for a content block");
            return;
        }
        Map<String, Object> event = event("content_block_delta", "index", openIndex);
        event.put("delta", delta);
        out.add(sse(event));
    }

    private void closeBlock(List<String> out) {
        if (openKey == null) {
            return;
        }
        openKey = null;
        out.add(sse(event("content_block_stop", "index", openIndex)));
    }

    private void textDelta(String key, String text, List<String> out) {
        if (!isOpen(key)) {
            openBlock(key, block("text", "text", ""), out);
        }
        delta(block("text_delta", "text", text), out);
    }

    private void finish(Response response, List<String> out) {
        start(out);
        if (nextIndex == 0) {
            openBlock("text", block("text", "text", ""), out);
        }
        closeBlock(out);
        terminated = true;
        String stopReason = ResponseTranslator.stopReason(ResponseTranslator.incompleteReason(response), false, false);
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("stop_reason", stopReason);
        delta.put("stop_sequence", null);
        Map<String, Object> messageDelta = event("message_delta", "delta", delta);
        messageDelta.put("usage", deltaUsage(ResponseTranslator.usage(response)));
        out.add(sse(messageDelta));
        out.add(sse(Map.of("type", "message_stop")));
    }

    /** The counters of the final usage, in the order of Anthropic's {@code message_delta}. */
    private static Map<String, Object> deltaUsage(Map<String, Object> usage) {
        Map<String, Object> delta = new LinkedHashMap<>();
        for (String name : List.of(
                "input_tokens",
                "output_tokens",
                "cache_creation_input_tokens",
                "cache_read_input_tokens",
                "output_tokens_details",
                "server_tool_use")) {
            Optional.ofNullable(usage.get(name)).ifPresent(value -> delta.put(name, value));
        }
        return delta;
    }

    private void error(int status, String message, List<String> out) {
        if (terminated) {
            return;
        }
        terminated = true;
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("type", ErrorCodes.anthropicType(status));
        error.put("message", message);
        out.add(sse(event("error", "error", error)));
    }

    private static Map<String, Object> event(String type, String key, Object value) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", type);
        event.put(key, value);
        return event;
    }

    private static Map<String, Object> block(String type, String key, Object value) {
        return new LinkedHashMap<>(event(type, key, value));
    }

    /** {@code event: <type>} and the compact JSON as data, which is how the Anthropic API writes an event. */
    private static String sse(Map<String, Object> event) {
        return "event: " + event.get("type") + "\ndata: " + Json.encode(event) + "\n\n";
    }
}
