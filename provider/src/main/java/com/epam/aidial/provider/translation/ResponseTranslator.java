package com.epam.aidial.provider.translation;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.OutputTokensDetails;
import com.anthropic.models.messages.ServerToolUsage;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.Usage;
import com.openai.errors.OpenAIInvalidDataException;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseFunctionWebSearch;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseOutputRefusal;
import com.openai.models.responses.ResponseOutputText;
import com.openai.models.responses.ResponseReasoningItem;
import com.openai.models.responses.ResponseStatus;
import com.openai.models.responses.ResponseUsage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Responses API response as a Messages API message, built with the Anthropic SDK's models. What the Responses API
 * does not say is null, as the SDK has it (the {@code container}, the {@code stop_sequence}, the {@code usage}'s {@code
 * service_tier}, ...). Pure.
 *
 * <p>Items and parts are told apart by the SDK's visitors. A type the translator does not handle is dropped, with a
 * warning; one that has no type is not a Responses API item.
 */
final class ResponseTranslator {

    private static final Logger log = LoggerFactory.getLogger(ResponseTranslator.class);

    private ResponseTranslator() {}

    /**
     * @param model the model the client asked for, as the deployment names it
     * @throws ResponseFailedException when the response is a failed one
     * @throws com.openai.errors.OpenAIInvalidDataException when a field it reads is missing or mistyped
     */
    static Message message(Response response, String model, ToolNames names) {
        if (response.status()
                .map(status -> status.value() == ResponseStatus.Value.FAILED)
                .orElse(false)) {
            throw failure(response);
        }
        OutputItems items = new OutputItems(names);
        response.output().forEach(item -> item.accept(items));
        List<ContentBlock> content = items.content;
        if (content.isEmpty()) {
            content.add(Blocks.text(""));
        }
        return messageBuilder(response.id(), model)
                .content(content)
                .stopReason(stopReason(incompleteReason(response), items.hasToolCall, items.isRefusal))
                .usage(usage(response))
                .build();
    }

    /** A message with what the translator never says (the container, the stop sequence, ...) said to be null. */
    static Message.Builder messageBuilder(String id, String model) {
        return Message.builder()
                .id(id)
                .model(Model.of(model))
                .container(Optional.empty())
                .diagnostics(Optional.empty())
                .stopDetails(Optional.empty())
                .stopSequence(Optional.empty());
    }

    static Optional<Response.IncompleteDetails.Reason> incompleteReason(Response response) {
        return response.incompleteDetails().flatMap(details -> details.reason());
    }

    /** What the answer was cut by, if it was; else what it holds: a tool call, a refusal, or neither. */
    static StopReason stopReason(
            Optional<Response.IncompleteDetails.Reason> incompleteReason, boolean hasToolCall, boolean isRefusal) {
        Response.IncompleteDetails.Reason.Value reason =
                incompleteReason.map(Response.IncompleteDetails.Reason::value).orElse(null);
        if (reason == Response.IncompleteDetails.Reason.Value.MAX_OUTPUT_TOKENS) {
            return StopReason.MAX_TOKENS;
        }
        if (reason == Response.IncompleteDetails.Reason.Value.CONTENT_FILTER) {
            return StopReason.REFUSAL;
        }
        if (hasToolCall) {
            return StopReason.TOOL_USE;
        }
        return isRefusal ? StopReason.REFUSAL : StopReason.END_TURN;
    }

    /**
     * The reasoning as a thinking block: its texts (summary, then content) joined by a blank line, and the encrypted
     * content as the signature, the wrapper that lets the next turn replay it. Without text, the encrypted content is a
     * redacted block; without either, nothing.
     */
    static Optional<ContentBlock> thinkingBlock(ResponseReasoningItem item) {
        List<String> texts = new ArrayList<>();
        item.summary().forEach(part -> texts.add(part.text()));
        item.content().ifPresent(parts -> parts.forEach(part -> texts.add(part.text())));
        String text = texts.stream().filter(t -> !t.isEmpty()).collect(Collectors.joining("\n\n"));
        String signature = item.encryptedContent()
                .filter(encrypted -> !encrypted.isEmpty())
                .map(encrypted -> new ReasoningWrapper(item.id(), encrypted).encode())
                .orElse("");
        if (!text.isEmpty()) {
            return Optional.of(Blocks.thinking(text, signature));
        }
        return signature.isEmpty() ? Optional.empty() : Optional.of(Blocks.redactedThinking(signature));
    }

    private static final class OutputItems implements ResponseOutputItem.Visitor<Void> {

        final List<ContentBlock> content = new ArrayList<>();
        boolean hasToolCall;
        boolean isRefusal;
        private final Set<String> seenUrls = new HashSet<>();
        private final ToolNames names;

        OutputItems(ToolNames names) {
            this.names = names;
        }

        @Override
        public Void visitReasoning(ResponseReasoningItem reasoning) {
            thinkingBlock(reasoning).ifPresent(content::add);
            return null;
        }

        @Override
        public Void visitMessage(ResponseOutputMessage message) {
            MessageParts parts = new MessageParts(seenUrls, content);
            message.content().forEach(part -> part.accept(parts));
            isRefusal |= parts.isRefusal;
            return null;
        }

        @Override
        public Void visitFunctionCall(ResponseFunctionToolCall call) {
            hasToolCall = true;
            content.add(toolUse(call, names));
            return null;
        }

        /** Any other item, which the SDK hands here too when it has no use for it. */
        @Override
        public Void unknown(com.openai.core.JsonValue json) {
            log.warn(
                    "Dropping an unsupported Responses API output item: {}",
                    ResponsesJson.requiredType(Optional.ofNullable(json), "output item"));
            return null;
        }
    }

    private static final class MessageParts implements ResponseOutputMessage.Content.Visitor<Void> {

        boolean isRefusal;
        private final Set<String> seenUrls;
        private final List<ContentBlock> content;

        MessageParts(Set<String> seenUrls, List<ContentBlock> content) {
            this.seenUrls = seenUrls;
            this.content = content;
        }

        @Override
        public Void visitOutputText(ResponseOutputText text) {
            content.add(Blocks.text(text.text()));
            content.addAll(WebSearchBlocks.of(newCitations(text, seenUrls)));
            return null;
        }

        @Override
        public Void visitRefusal(ResponseOutputRefusal refusal) {
            isRefusal = true;
            content.add(Blocks.text(refusal.refusal()));
            return null;
        }

        @Override
        public Void unknown(com.openai.core.JsonValue json) {
            log.warn(
                    "Dropping an unsupported Responses API content part: {}",
                    ResponsesJson.requiredType(Optional.ofNullable(json), "output content part"));
            return null;
        }
    }

    static boolean hasRefusal(ResponseOutputMessage message) {
        return message.content().stream().anyMatch(ResponseOutputMessage.Content::isRefusal);
    }

    static List<ContentBlock> citationBlocks(ResponseOutputMessage message, Set<String> seenUrls) {
        List<WebSearchBlocks.Citation> citations = new ArrayList<>();
        message.content().stream()
                .flatMap(part -> part.outputText().stream())
                .forEach(text -> citations.addAll(newCitations(text, seenUrls)));
        return WebSearchBlocks.of(citations);
    }

    private static List<WebSearchBlocks.Citation> newCitations(ResponseOutputText text, Set<String> seenUrls) {
        List<WebSearchBlocks.Citation> citations = new ArrayList<>();
        for (ResponseOutputText.Annotation annotation : text.annotations()) {
            annotation.urlCitation().ifPresent(citation -> {
                if (seenUrls.add(citation.url())) {
                    citations.add(new WebSearchBlocks.Citation(citation.url(), citation.title()));
                }
            });
        }
        return citations;
    }

    private static ContentBlock toolUse(ResponseFunctionToolCall call, ToolNames names) {
        return Blocks.toolUse(call.callId(), names.toClient(call.name()), arguments(call.arguments()));
    }

    /** The arguments as an object; {@code {}} for what is none. */
    private static JsonValue arguments(String arguments) {
        if (!arguments.isEmpty()) {
            Optional<JsonValue> object = AnthropicJson.object(arguments);
            if (object.isPresent()) {
                return object.get();
            }
            log.warn("Failed to parse function_call arguments as a JSON object");
        }
        return JsonValue.from(Map.of());
    }

    static ResponseFailedException failure(Response response) {
        return response.error()
                .map(error -> new ResponseFailedException(
                        ErrorCodes.status(error.code().asString()), error.message()))
                .orElseGet(() -> new ResponseFailedException(500, "The Responses API response has failed"));
    }

    /**
     * The tokens, as Anthropic counts them: {@code input_tokens} excludes the cache reads and writes, which the
     * Responses API's include, so the breakdown is capped for the counters to add up. With no usage at all, the tokens
     * are 0 and the rest is not known.
     */
    static Usage usage(Response response) {
        ResponseUsage tokens = response.usage().orElse(null);
        if (tokens == null) {
            return usageBuilder()
                    .inputTokens(0)
                    .outputTokens(0)
                    .cacheReadInputTokens(Optional.empty())
                    .cacheCreationInputTokens(Optional.empty())
                    .outputTokensDetails(Optional.empty())
                    .serverToolUse(Optional.empty())
                    .build();
        }
        long input;
        long output;
        long cached;
        long written;
        long thinking;
        try {
            input = tokens.inputTokens();
            output = tokens.outputTokens();
            cached = tokens.inputTokensDetails().cachedTokens();
            written = tokens.inputTokensDetails().cacheWriteTokens();
            thinking = tokens.outputTokensDetails().reasoningTokens();
        } catch (OpenAIInvalidDataException e) {
            log.debug("Invalid usage: {}", tokens, e);
            throw new OpenAIInvalidDataException("The usage is invalid", e);
        }
        long cacheRead = Math.min(cached, input);
        long cacheWrite = Math.min(written, input - cacheRead);
        return usageBuilder()
                .inputTokens(input - cacheRead - cacheWrite)
                .outputTokens(output)
                .cacheReadInputTokens(cacheRead)
                .cacheCreationInputTokens(cacheWrite)
                .outputTokensDetails(
                        OutputTokensDetails.builder().thinkingTokens(thinking).build())
                .serverToolUse(webSearchCounts(response.output()))
                .build();
    }

    /** A usage with what the translator never says (the cache's TTLs, the service tier, ...) said to be null. */
    static Usage.Builder usageBuilder() {
        return Usage.builder()
                .cacheCreation(Optional.empty())
                .inferenceGeo(Optional.empty())
                .serviceTier(Optional.empty())
                .speed(Optional.empty());
    }

    static Optional<ServerToolUsage> webSearchCounts(List<ResponseOutputItem> output) {
        long searches = 0;
        long fetches = 0;
        for (ResponseOutputItem item : output) {
            if (!item.isWebSearchCall()) {
                continue;
            }
            ResponseFunctionWebSearch call = item.asWebSearchCall();
            ResponseFunctionWebSearch.Action action = call.action();
            if (action.search().isPresent()) {
                searches++;
            } else if (action.openPage().isPresent()) {
                fetches++;
            }
        }
        if (searches == 0 && fetches == 0) {
            return Optional.empty();
        }
        return Optional.of(ServerToolUsage.builder()
                .webSearchRequests(searches)
                .webFetchRequests(fetches)
                .build());
    }

    static final class ResponseFailedException extends RuntimeException {

        private final int status;

        ResponseFailedException(int status, String message) {
            super(message, null, false, false);
            this.status = status;
        }

        int status() {
            return status;
        }
    }
}
