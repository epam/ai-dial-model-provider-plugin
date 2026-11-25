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
        return terminated ? List.of() : List.of(sse("ping", Map.of("type", "ping")));
    }

    /** The data of one event of the upstream's stream. */
    List<String> onData(String data) {
        if (terminated) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        try {
            ResponseStreamEvent event = ResponsesJson.parseEvent(data.getBytes(StandardCharsets.UTF_8));
            event.accept(new Events(out));
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

    /**
     * What each event of the upstream's stream does. The SDK hands an event this does not override to {@link
     * #unknown}, as it does an event of a type it does not know.
     */
    private final class Events implements ResponseStreamEvent.Visitor<Void> {

        private final List<String> out;

        Events(List<String> out) {
            this.out = out;
        }

        @Override
        public Void visitCreated(ResponseCreatedEvent created) {
            messageId = created.response().id();
            start(out);
            return null;
        }

        @Override
        public Void visitOutputItemAdded(ResponseOutputItemAddedEvent added) {
            itemAdded(added, out);
            return null;
        }

        @Override
        public Void visitOutputTextDelta(ResponseTextDeltaEvent delta) {
            textDelta("text:" + delta.outputIndex() + ":" + delta.contentIndex(), delta.delta(), out);
            return null;
        }

        @Override
        public Void visitRefusalDelta(ResponseRefusalDeltaEvent delta) {
            textDelta("text:" + delta.outputIndex() + ":" + delta.contentIndex(), delta.delta(), out);
            return null;
        }

        @Override
        public Void visitFunctionCallArgumentsDelta(ResponseFunctionCallArgumentsDeltaEvent delta) {
            argumentsDelta(delta, out);
            return null;
        }

        @Override
        public Void visitReasoningSummaryTextDelta(ResponseReasoningSummaryTextDeltaEvent delta) {
            thinkingDelta(delta.outputIndex(), "summary:" + delta.summaryIndex(), delta.delta(), out);
            return null;
        }

        @Override
        public Void visitReasoningTextDelta(ResponseReasoningTextDeltaEvent delta) {
            thinkingDelta(delta.outputIndex(), "content:" + delta.contentIndex(), delta.delta(), out);
            return null;
        }

        @Override
        public Void visitOutputItemDone(ResponseOutputItemDoneEvent done) {
            itemDone(done, out);
            return null;
        }

        @Override
        public Void visitCompleted(ResponseCompletedEvent completed) {
            finish(completed.response(), out);
            return null;
        }

        @Override
        public Void visitIncomplete(ResponseIncompleteEvent incomplete) {
            finish(incomplete.response(), out);
            return null;
        }

        @Override
        public Void visitFailed(ResponseFailedEvent failed) {
            ResponseTranslator.ResponseFailedException failure = ResponseTranslator.failure(failed.response());
            error(failure.status(), failure.getMessage(), out);
            return null;
        }

        @Override
        public Void visitError(ResponseErrorEvent failure) {
            error(ErrorCodes.status(failure.code().orElse("")), failure.message(), out);
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

    private void start(List<String> out) {
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
        out.add(sse(RawMessageStartEvent.of(ResponseTranslator.messageBuilder(messageId, model)
                .content(List.of())
                .stopReason(Optional.empty())
                .usage(usage)
                .build())));
    }

    private void openBlock(String key, ContentBlock block, List<String> out) {
        start(out);
        closeBlock(out);
        openKey = key;
        openIndex = nextIndex++;
        out.add(sse(RawContentBlockStartEvent.builder()
                .index(openIndex)
                .contentBlock(Blocks.opening(block))
                .build()));
    }

    private boolean isOpen(String key) {
        return key.equals(openKey);
    }

    private void delta(UnaryOperator<RawContentBlockDeltaEvent.Builder> delta, List<String> out) {
        if (openKey == null) {
            log.warn("Delta event has arrived before open event for a content block");
            return;
        }
        out.add(sse(delta.apply(RawContentBlockDeltaEvent.builder().index(openIndex))
                .build()));
    }

    private void closeBlock(List<String> out) {
        if (openKey == null) {
            return;
        }
        openKey = null;
        out.add(sse(RawContentBlockStopEvent.builder().index(openIndex).build()));
    }

    private void textDelta(String key, String text, List<String> out) {
        if (!isOpen(key)) {
            openBlock(key, Blocks.text(""), out);
        }
        delta(chunk -> chunk.textDelta(text), out);
    }

    private void thinkingDelta(long outputIndex, String part, String text, List<String> out) {
        if (text.isEmpty()) {
            return;
        }
        String key = "reasoning:" + outputIndex;
        String shown = text;
        if (!isOpen(key)) {
            openBlock(key, Blocks.thinking("", ""), out);
        } else if (!part.equals(reasoningPart)) {
            // Separates the summary parts the way a block response joins them
            shown = "\n\n" + text;
        }
        reasoningPart = part;
        String thought = shown;
        delta(chunk -> chunk.thinkingDelta(thought), out);
    }

    private void itemAdded(ResponseOutputItemAddedEvent added, List<String> out) {
        added.item().accept(new ResponseOutputItem.Visitor<Void>() {
            @Override
            public Void visitFunctionCall(ResponseFunctionToolCall call) {
                hasToolCall = true;
                openBlock(
                        "function:" + added.outputIndex(),
                        Blocks.toolUse(call.callId(), names.toClient(call.name()), JsonValue.from(Map.of())),
                        out);
                return null;
            }

            @Override
            public Void unknown(com.openai.core.JsonValue json) {
                return null;
            }
        });
    }

    private void argumentsDelta(ResponseFunctionCallArgumentsDeltaEvent delta, List<String> out) {
        if (!isOpen("function:" + delta.outputIndex()) || delta.delta().isEmpty()) {
            return;
        }
        streamedArguments.add(delta.outputIndex());
        delta(chunk -> chunk.inputJsonDelta(delta.delta()), out);
    }

    private void itemDone(ResponseOutputItemDoneEvent done, List<String> out) {
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
                    delta(chunk -> chunk.inputJsonDelta(arguments), out);
                }
                closeBlock(out);
                return null;
            }

            @Override
            public Void visitReasoning(ResponseReasoningItem reasoning) {
                reasoningDone("reasoning:" + done.outputIndex(), reasoning, out);
                return null;
            }

            @Override
            public Void visitMessage(ResponseOutputMessage message) {
                for (ContentBlock citation : ResponseTranslator.citationBlocks(message, seenUrls)) {
                    openBlock("citation", citation, out);
                    closeBlock(out);
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
    private void reasoningDone(String key, ResponseReasoningItem item, List<String> out) {
        if (isOpen(key)) {
            item.encryptedContent()
                    .filter(encrypted -> !encrypted.isEmpty())
                    .ifPresent(encrypted -> delta(
                            chunk -> chunk.signatureDelta(new ReasoningWrapper(item.id(), encrypted).encode()), out));
            closeBlock(out);
        } else {
            ResponseTranslator.thinkingBlock(item).ifPresent(block -> {
                openBlock(key, block, out);
                closeBlock(out);
            });
        }
    }

    private void finish(Response response, List<String> out) {
        start(out);
        if (nextIndex == 0) {
            openBlock("text", Blocks.text(""), out);
        }
        closeBlock(out);
        terminated = true;
        StopReason stopReason =
                ResponseTranslator.stopReason(ResponseTranslator.incompleteReason(response), hasToolCall, isRefusal);
        out.add(sse(RawMessageDeltaEvent.builder()
                .delta(RawMessageDeltaEvent.Delta.builder()
                        .container(Optional.empty())
                        .stopDetails(Optional.empty())
                        .stopReason(stopReason)
                        .stopSequence(Optional.empty())
                        .build())
                .usage(deltaUsage(ResponseTranslator.usage(response)))
                .build()));
        out.add(sse(RawMessageStopEvent.builder().build()));
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

    private void error(int status, String message, List<String> out) {
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

    private static String sse(RawMessageStartEvent event) {
        return sse("message_start", RawMessageStreamEvent.ofMessageStart(event));
    }

    private static String sse(RawContentBlockStartEvent event) {
        return sse("content_block_start", RawMessageStreamEvent.ofContentBlockStart(event));
    }

    private static String sse(RawContentBlockDeltaEvent event) {
        return sse("content_block_delta", RawMessageStreamEvent.ofContentBlockDelta(event));
    }

    private static String sse(RawContentBlockStopEvent event) {
        return sse("content_block_stop", RawMessageStreamEvent.ofContentBlockStop(event));
    }

    private static String sse(RawMessageDeltaEvent event) {
        return sse("message_delta", RawMessageStreamEvent.ofMessageDelta(event));
    }

    private static String sse(RawMessageStopEvent event) {
        return sse("message_stop", RawMessageStreamEvent.ofMessageStop(event));
    }

    /** {@code event: <type>} and the compact JSON as data, which is how the Anthropic API writes an event. */
    private static String sse(String type, Object event) {
        return "event: " + type + "\ndata: " + AnthropicJson.write(event) + "\n\n";
    }
}
