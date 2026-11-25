package com.epam.aidial.provider.translation;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.MessageDeltaUsage;
import com.anthropic.models.messages.RawContentBlockDeltaEvent;
import com.anthropic.models.messages.RawContentBlockStartEvent;
import com.anthropic.models.messages.RawContentBlockStopEvent;
import com.anthropic.models.messages.RawMessageDeltaEvent;
import com.anthropic.models.messages.RawMessageStartEvent;
import com.anthropic.models.messages.RawMessageStopEvent;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.Usage;
import com.openai.errors.OpenAIInvalidDataException;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCompletedEvent;
import com.openai.models.responses.ResponseCreatedEvent;
import com.openai.models.responses.ResponseErrorEvent;
import com.openai.models.responses.ResponseFailedEvent;
import com.openai.models.responses.ResponseFunctionCallArgumentsDeltaEvent;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseIncompleteEvent;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputItemAddedEvent;
import com.openai.models.responses.ResponseOutputItemDoneEvent;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseReasoningItem;
import com.openai.models.responses.ResponseReasoningSummaryTextDeltaEvent;
import com.openai.models.responses.ResponseReasoningTextDeltaEvent;
import com.openai.models.responses.ResponseRefusalDeltaEvent;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.models.responses.ResponseTextDeltaEvent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A stream of Responses API events as a stream of Messages API events (the Anthropic SDK's models, but for {@code
 * ping} and {@code error}, which it has none for): a state machine that is told each event and answers with the SSE
 * text to send. One content block is open at a time; opening one closes the one before. After the
 * message has ended, or has failed, nothing more is said. Pure: no I/O and no Vert.x.
 *
 * <p>A failure of the stream (an event that is not one, an error event, an end without the final event) is an {@code
 * error} event, the response being 200 already.
 */
final class StreamTranslator {

    private static final Logger log = LoggerFactory.getLogger(StreamTranslator.class);

    private final String model;
    private final ToolNames names;
    /** The SSE text said by the event being told, which {@link #drain} hands over. */
    private final List<String> out = new ArrayList<>();

    private String messageId = java.util.UUID.randomUUID().toString();
    private int nextIndex;
    private boolean terminated;
    private boolean started;
    private final java.util.Set<String> warnedTypes = new java.util.HashSet<>();
    private @Nullable String openKey;
    private int openIndex;
    private boolean hasToolCall;
    private boolean isRefusal;
    private final Set<Long> streamedArguments = new HashSet<>();
    private @Nullable String reasoningPart;
    private final Set<String> seenUrls = new HashSet<>();

    StreamTranslator(String model, ToolNames names) {
        this.model = model;
        this.names = names;
    }

    /** The upstream is alive, and says it between two events. */
    List<String> onHeartbeat() {
        if (!terminated) {
            out.add(sse("ping", Map.of("type", "ping")));
        }
        return drain();
    }

    /** The data of one event of the upstream's stream. */
    List<String> onData(String data) {
        if (terminated) {
            return List.of();
        }
        try {
            ResponseStreamEvent event = ResponsesJson.parseEvent(data.getBytes(StandardCharsets.UTF_8));
            event.accept(new Events());
        } catch (UpstreamResponseException | OpenAIInvalidDataException e) {
            log.error("The Responses API stream holds what is not a Responses API event: {}", e.getMessage());
            error(500, "Internal server error");
        } catch (RuntimeException e) {
            log.error("A Responses API event could not be translated", e);
            error(500, "Internal server error");
        }
        return drain();
    }

    /** The upstream's stream ended. */
    List<String> onEnd() {
        if (!terminated) {
            log.warn("The Responses API stream ended without a final event");
            error(502, "The Responses API stream ended before the response completed");
        }
        return drain();
    }

    /** The upstream's stream broke. */
    List<String> onFailure(Throwable cause) {
        if (!terminated) {
            log.error("The Responses API stream failed", cause);
            error(500, "Internal server error");
        }
        return drain();
    }

    /** The events said since the last call, which the next call starts over from. */
    private List<String> drain() {
        List<String> events = List.copyOf(out);
        out.clear();
        return events;
    }

    /**
     * What each event of the upstream's stream does. The SDK hands an event this does not override to {@link
     * #unknown}, as it does an event of a type it does not know.
     */
    private final class Events implements ResponseStreamEvent.Visitor<Void> {

        @Override
        public Void visitCreated(ResponseCreatedEvent created) {
            messageId = created.response().id();
            start();
            return null;
        }

        @Override
        public Void visitOutputItemAdded(ResponseOutputItemAddedEvent added) {
            itemAdded(added);
            return null;
        }

        @Override
        public Void visitOutputTextDelta(ResponseTextDeltaEvent delta) {
            textDelta("text:" + delta.outputIndex() + ":" + delta.contentIndex(), delta.delta());
            return null;
        }

        @Override
        public Void visitRefusalDelta(ResponseRefusalDeltaEvent delta) {
            textDelta("text:" + delta.outputIndex() + ":" + delta.contentIndex(), delta.delta());
            return null;
        }

        @Override
        public Void visitFunctionCallArgumentsDelta(ResponseFunctionCallArgumentsDeltaEvent delta) {
            argumentsDelta(delta);
            return null;
        }

        @Override
        public Void visitReasoningSummaryTextDelta(ResponseReasoningSummaryTextDeltaEvent delta) {
            thinkingDelta(delta.outputIndex(), "summary:" + delta.summaryIndex(), delta.delta());
            return null;
        }

        @Override
        public Void visitReasoningTextDelta(ResponseReasoningTextDeltaEvent delta) {
            thinkingDelta(delta.outputIndex(), "content:" + delta.contentIndex(), delta.delta());
            return null;
        }

        @Override
        public Void visitOutputItemDone(ResponseOutputItemDoneEvent done) {
            itemDone(done);
            return null;
        }

        @Override
        public Void visitCompleted(ResponseCompletedEvent completed) {
            finish(completed.response());
            return null;
        }

        @Override
        public Void visitIncomplete(ResponseIncompleteEvent incomplete) {
            finish(incomplete.response());
            return null;
        }

        @Override
        public Void visitFailed(ResponseFailedEvent failed) {
            ResponseTranslator.ResponseFailedException failure = ResponseTranslator.failure(failed.response());
            error(failure.status(), failure.getMessage());
            return null;
        }

        @Override
        public Void visitError(ResponseErrorEvent failure) {
            error(ErrorCodes.status(failure.code().orElse("")), failure.message());
            return null;
        }

        @Override
        public Void unknown(com.openai.core.JsonValue json) {
            dropped(ResponsesJson.requiredType(Optional.ofNullable(json), "event"));
            return null;
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

    private void start() {
        if (started) {
            return;
        }
        started = true;
        Usage usage = ResponseTranslator.usageBuilder()
                .inputTokens(0)
                .outputTokens(0)
                .cacheCreationInputTokens(0)
                .cacheReadInputTokens(0)
                .outputTokensDetails(Optional.empty())
                .serverToolUse(Optional.empty())
                .build();
        out.add(sse(RawMessageStreamEvent.ofMessageStart(
                RawMessageStartEvent.of(ResponseTranslator.messageBuilder(messageId, model)
                        .content(List.of())
                        .stopReason(Optional.empty())
                        .usage(usage)
                        .build()))));
    }

    private void openBlock(String key, ContentBlock block) {
        start();
        closeBlock();
        openKey = key;
        openIndex = nextIndex++;
        out.add(sse(RawMessageStreamEvent.ofContentBlockStart(RawContentBlockStartEvent.builder()
                .index(openIndex)
                .contentBlock(Blocks.opening(block))
                .build())));
    }

    private boolean isOpen(String key) {
        return key.equals(openKey);
    }

    private void delta(UnaryOperator<RawContentBlockDeltaEvent.Builder> delta) {
        if (openKey == null) {
            log.warn("Delta event has arrived before open event for a content block");
            return;
        }
        out.add(sse(RawMessageStreamEvent.ofContentBlockDelta(
                delta.apply(RawContentBlockDeltaEvent.builder().index(openIndex))
                        .build())));
    }

    private void closeBlock() {
        if (openKey == null) {
            return;
        }
        openKey = null;
        out.add(sse(RawMessageStreamEvent.ofContentBlockStop(
                RawContentBlockStopEvent.builder().index(openIndex).build())));
    }

    private void textDelta(String key, String text) {
        if (!isOpen(key)) {
            openBlock(key, Blocks.text(""));
        }
        delta(chunk -> chunk.textDelta(text));
    }

    private void thinkingDelta(long outputIndex, String part, String text) {
        if (text.isEmpty()) {
            return;
        }
        String key = "reasoning:" + outputIndex;
        String shown = text;
        if (!isOpen(key)) {
            openBlock(key, Blocks.thinking("", ""));
        } else if (!part.equals(reasoningPart)) {
            // Separates the summary parts the way a block response joins them
            shown = "\n\n" + text;
        }
        reasoningPart = part;
        String thought = shown;
        delta(chunk -> chunk.thinkingDelta(thought));
    }

    private void itemAdded(ResponseOutputItemAddedEvent added) {
        added.item().accept(new ResponseOutputItem.Visitor<Void>() {
            @Override
            public Void visitFunctionCall(ResponseFunctionToolCall call) {
                hasToolCall = true;
                openBlock(
                        "function:" + added.outputIndex(),
                        Blocks.toolUse(call.callId(), names.toClient(call.name()), JsonValue.from(Map.of())));
                return null;
            }

            @Override
            public Void unknown(com.openai.core.JsonValue json) {
                return null;
            }
        });
    }

    private void argumentsDelta(ResponseFunctionCallArgumentsDeltaEvent delta) {
        if (!isOpen("function:" + delta.outputIndex()) || delta.delta().isEmpty()) {
            return;
        }
        streamedArguments.add(delta.outputIndex());
        delta(chunk -> chunk.inputJsonDelta(delta.delta()));
    }

    private void itemDone(ResponseOutputItemDoneEvent done) {
        done.item().accept(new ResponseOutputItem.Visitor<Void>() {
            @Override
            public Void visitFunctionCall(ResponseFunctionToolCall call) {
                String key = "function:" + done.outputIndex();
                if (!isOpen(key)) {
                    return null;
                }
                String arguments = call.arguments();
                if (!streamedArguments.contains(done.outputIndex()) && !arguments.isEmpty()) {
                    // The arguments an upstream sent without the deltas
                    delta(chunk -> chunk.inputJsonDelta(arguments));
                }
                closeBlock();
                return null;
            }

            @Override
            public Void visitReasoning(ResponseReasoningItem reasoning) {
                reasoningDone("reasoning:" + done.outputIndex(), reasoning);
                return null;
            }

            @Override
            public Void visitMessage(ResponseOutputMessage message) {
                for (ContentBlock citation : ResponseTranslator.citationBlocks(message, seenUrls)) {
                    openBlock("citation", citation);
                    closeBlock();
                }
                isRefusal |= ResponseTranslator.hasRefusal(message);
                return null;
            }

            @Override
            public Void unknown(com.openai.core.JsonValue json) {
                log.warn(
                        "Dropping an unsupported Responses API output item: {}",
                        ResponsesJson.type(Optional.ofNullable(json)).orElse("(none)"));
                return null;
            }
        });
    }

    /**
     * The encrypted reasoning arrives once its text has been streamed: it is the signature of the open block. A
     * reasoning whose text was not streamed is one whole block.
     */
    private void reasoningDone(String key, ResponseReasoningItem item) {
        if (isOpen(key)) {
            item.encryptedContent()
                    .filter(encrypted -> !encrypted.isEmpty())
                    .ifPresent(encrypted ->
                            delta(chunk -> chunk.signatureDelta(new ReasoningWrapper(item.id(), encrypted).encode())));
            closeBlock();
        } else {
            ResponseTranslator.thinkingBlock(item).ifPresent(block -> {
                openBlock(key, block);
                closeBlock();
            });
        }
    }

    private void finish(Response response) {
        start();
        if (nextIndex == 0) {
            openBlock("text", Blocks.text(""));
        }
        closeBlock();
        terminated = true;
        StopReason stopReason =
                ResponseTranslator.stopReason(ResponseTranslator.incompleteReason(response), hasToolCall, isRefusal);
        out.add(sse(RawMessageStreamEvent.ofMessageDelta(RawMessageDeltaEvent.builder()
                .delta(RawMessageDeltaEvent.Delta.builder()
                        .container(Optional.empty())
                        .stopDetails(Optional.empty())
                        .stopReason(stopReason)
                        .stopSequence(Optional.empty())
                        .build())
                .usage(deltaUsage(ResponseTranslator.usage(response)))
                .build())));
        out.add(sse(RawMessageStreamEvent.ofMessageStop(
                RawMessageStopEvent.builder().build())));
    }

    /** The counters of the final usage that {@code message_delta} carries. */
    private static MessageDeltaUsage deltaUsage(Usage usage) {
        return MessageDeltaUsage.builder()
                .inputTokens(usage.inputTokens())
                .outputTokens(usage.outputTokens())
                .cacheCreationInputTokens(usage.cacheCreationInputTokens())
                .cacheReadInputTokens(usage.cacheReadInputTokens())
                .outputTokensDetails(usage.outputTokensDetails())
                .serverToolUse(usage.serverToolUse())
                .build();
    }

    private void error(int status, String message) {
        if (terminated) {
            return;
        }
        terminated = true;
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("type", ErrorCodes.anthropicType(status));
        error.put("message", message);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "error");
        event.put("error", error);
        out.add(sse("error", event));
    }

    /** The event under the {@code type} it holds. */
    private static String sse(RawMessageStreamEvent event) {
        return sse(event.type().asString(), event);
    }

    /** {@code event: <type>} and the compact JSON as data, which is how the Anthropic API writes an event. */
    private static String sse(String type, Object event) {
        return "event: " + type + "\ndata: " + AnthropicJson.write(event) + "\n\n";
    }
}
