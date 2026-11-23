package com.epam.aidial.provider.translation;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.OutputTokensDetails;
import com.anthropic.models.messages.ServerToolUsage;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.Usage;
import com.epam.aidial.provider.json.Json;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseFunctionWebSearch;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseOutputText;
import com.openai.models.responses.ResponseReasoningItem;
import com.openai.models.responses.ResponseUsage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
 * <p>Items and parts are told by their {@code type} as sent: the SDK's union takes an item that is none of its types
 * for the one it resembles most. A type the translator does not know is dropped, with a warning; one that has no type
 * is not a Responses API item.
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
        if (response.status().map(status -> status.asString().equals("failed")).orElse(false)) {
            throw failure(response);
        }
        List<ContentBlock> content = new ArrayList<>();
        boolean hasToolCall = false;
        boolean isRefusal = false;
        Set<String> seenUrls = new HashSet<>();
        for (ResponseOutputItem item : response.output()) {
            String type = ResponsesJson.requiredType(item._json(), "output item");
            switch (type) {
                case "reasoning" ->
                    item.reasoning().flatMap(ResponseTranslator::thinkingBlock).ifPresent(content::add);
                case "message" -> {
                    ResponseOutputMessage message = item.message().orElseThrow();
                    isRefusal |= messageBlocks(message, seenUrls, content);
                }
                case "function_call" -> {
                    hasToolCall = true;
                    content.add(toolUse(item.functionCall().orElseThrow(), names));
                }
                default -> log.warn("Dropping an unsupported Responses API output item: {}", type);
            }
        }
        if (content.isEmpty()) {
            content.add(Blocks.text(""));
        }
        return messageBuilder(response.id(), model)
                .content(content)
                .stopReason(stopReason(incompleteReason(response), hasToolCall, isRefusal))
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

    static Optional<String> incompleteReason(Response response) {
        return response.incompleteDetails().flatMap(details -> details.reason()).map(reason -> reason.asString());
    }

    /** What the answer was cut by, if it was; else what it holds: a tool call, a refusal, or neither. */
    static StopReason stopReason(Optional<String> incompleteReason, boolean hasToolCall, boolean isRefusal) {
        String reason = incompleteReason.orElse("");
        if (reason.equals("max_output_tokens")) {
            return StopReason.MAX_TOKENS;
        }
        if (reason.equals("content_filter")) {
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

    /** The blocks of a message; whether it holds a refusal. */
    private static boolean messageBlocks(
            ResponseOutputMessage message, Set<String> seenUrls, List<ContentBlock> content) {
        boolean isRefusal = false;
        for (ResponseOutputMessage.Content part : message.content()) {
            String type = ResponsesJson.requiredType(part._json(), "output content part");
            switch (type) {
                case "output_text" -> {
                    ResponseOutputText text = part.outputText().orElseThrow();
                    content.add(Blocks.text(text.text()));
                    content.addAll(WebSearchBlocks.of(newCitations(text, seenUrls)));
                }
                case "refusal" -> {
                    isRefusal = true;
                    content.add(Blocks.text(part.refusal().orElseThrow().refusal()));
                }
                default -> log.warn("Dropping an unsupported Responses API content part: {}", type);
            }
        }
        return isRefusal;
    }

    /** Whether a message holds a refusal. */
    static boolean hasRefusal(ResponseOutputMessage message) {
        return message.content().stream().anyMatch(part -> "refusal"
                .equals(ResponsesJson.type(part._json()).orElse("")));
    }

    /** The web search blocks for the URLs a finished message cites for the first time. */
    static List<ContentBlock> citationBlocks(ResponseOutputMessage message, Set<String> seenUrls) {
        List<WebSearchBlocks.Citation> citations = new ArrayList<>();
        for (ResponseOutputMessage.Content part : message.content()) {
            if ("output_text".equals(ResponsesJson.type(part._json()).orElse(""))) {
                citations.addAll(newCitations(part.outputText().orElseThrow(), seenUrls));
            }
        }
        return WebSearchBlocks.of(citations);
    }

    /** The URL citations of a text that no text before it cited. */
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
        return Blocks.toolUse(call.callId(), names.toClient(call.name()), JsonValue.from(arguments(call.arguments())));
    }

    /** The arguments as an object; {@code {}} for what is none. */
    private static Map<String, Object> arguments(String arguments) {
        if (!arguments.isEmpty()) {
            try {
                if (Json.parse(arguments) instanceof Map<?, ?> object) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> typed = (Map<String, Object>) object;
                    return typed;
                }
            } catch (Json.ParseException e) {
                // reported below
            }
            log.warn("Failed to parse function_call arguments as a JSON object");
        }
        return new LinkedHashMap<>();
    }

    static ResponseFailedException failure(Response response) {
        return response.error()
                .map(error -> new ResponseFailedException(
                        ErrorCodes.status(error._code()
                                .asKnown()
                                .map(code -> code.asString())
                                .orElse("")),
                        error.message()))
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
        long input = ResponsesJson.count(tokens._inputTokens(), "usage.input_tokens");
        ResponseUsage.InputTokensDetails inputDetails = ResponsesJson.object(
                        tokens._inputTokensDetails(), "usage.input_tokens_details")
                .orElse(null);
        long cached = inputDetails == null
                ? 0
                : ResponsesJson.count(inputDetails._cachedTokens(), "usage.input_tokens_details.cached_tokens");
        long written = inputDetails == null
                ? 0
                : ResponsesJson.count(
                        inputDetails._cacheWriteTokens(), "usage.input_tokens_details.cache_write_tokens");
        long cacheRead = Math.min(cached, input);
        long cacheWrite = Math.min(written, input - cacheRead);
        long thinking = ResponsesJson.object(tokens._outputTokensDetails(), "usage.output_tokens_details")
                .map(details ->
                        ResponsesJson.count(details._reasoningTokens(), "usage.output_tokens_details.reasoning_tokens"))
                .orElse(0L);
        return usageBuilder()
                .inputTokens(input - cacheRead - cacheWrite)
                .outputTokens(ResponsesJson.count(tokens._outputTokens(), "usage.output_tokens"))
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

    /** What the web search calls of the answer did: a search, or the opening of a page. None, if there were none. */
    static Optional<ServerToolUsage> webSearchCounts(List<ResponseOutputItem> output) {
        long searches = 0;
        long fetches = 0;
        for (ResponseOutputItem item : output) {
            if (!"web_search_call".equals(ResponsesJson.type(item._json()).orElse(""))) {
                continue;
            }
            ResponseFunctionWebSearch call = item.webSearchCall().orElseThrow();
            Optional<ResponseFunctionWebSearch.Action> action = ResponsesJson.object(call._action(), "action");
            if (action.isPresent() && action.get().search().isPresent()) {
                searches++;
            } else if (action.isPresent() && action.get().openPage().isPresent()) {
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

    /** The response says it failed: the client gets the status the failure maps to. */
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
