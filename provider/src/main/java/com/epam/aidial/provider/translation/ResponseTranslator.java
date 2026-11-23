package com.epam.aidial.provider.translation;

import com.epam.aidial.provider.json.Json;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseFunctionToolCall;
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
 * The Responses API response as a Messages API message, sparse: only the fields set (no {@code stop_sequence}, a
 * {@code usage} of what is known). Pure.
 */
final class ResponseTranslator {

    private static final Logger log = LoggerFactory.getLogger(ResponseTranslator.class);

    private ResponseTranslator() {}

    /**
     * @param model the model the client asked for, as the deployment names it
     * @throws ResponseFailedException when the response is a failed one
     * @throws com.openai.errors.OpenAIInvalidDataException when a field it reads is missing or mistyped
     */
    static Map<String, Object> message(Response response, String model, ToolNames names) {
        if (response.status().map(status -> status.asString().equals("failed")).orElse(false)) {
            throw failure(response);
        }
        List<Map<String, Object>> content = new ArrayList<>();
        boolean hasToolCall = false;
        Set<String> seenUrls = new HashSet<>();
        for (ResponseOutputItem item : response.output()) {
            if (item.reasoning().isPresent()) {
                thinkingBlock(item.reasoning().get()).ifPresent(content::add);
            } else if (item.message().isPresent()) {
                messageBlocks(item.message().get(), seenUrls, content);
            } else if (item.functionCall().isPresent()) {
                hasToolCall = true;
                content.add(toolUse(item.functionCall().get(), names));
            } else {
                log.warn("Dropping an unsupported Responses API output item");
            }
        }
        if (content.isEmpty()) {
            content.add(textBlock(""));
        }
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", response.id());
        message.put("type", "message");
        message.put("role", "assistant");
        message.put("model", model);
        message.put("content", content);
        message.put("stop_reason", hasToolCall ? "tool_use" : "end_turn");
        message.put("usage", usage(response));
        return message;
    }

    /**
     * The reasoning as a thinking block: its texts (summary, then content) joined by a blank line, and the encrypted
     * content as the signature, the wrapper that lets the next turn replay it. Without text, the encrypted content is a
     * redacted block; without either, nothing.
     */
    private static Optional<Map<String, Object>> thinkingBlock(ResponseReasoningItem item) {
        List<String> texts = new ArrayList<>();
        item.summary().forEach(part -> texts.add(part.text()));
        item.content().ifPresent(parts -> parts.forEach(part -> texts.add(part.text())));
        String text = texts.stream().filter(t -> !t.isEmpty()).collect(Collectors.joining("\n\n"));
        String signature = item.encryptedContent()
                .filter(encrypted -> !encrypted.isEmpty())
                .map(encrypted -> new ReasoningWrapper(item.id(), encrypted).encode())
                .orElse("");
        Map<String, Object> block = new LinkedHashMap<>();
        if (!text.isEmpty()) {
            block.put("type", "thinking");
            block.put("thinking", text);
            block.put("signature", signature);
        } else if (!signature.isEmpty()) {
            block.put("type", "redacted_thinking");
            block.put("data", signature);
        } else {
            return Optional.empty();
        }
        return Optional.of(block);
    }

    private static void messageBlocks(
            ResponseOutputMessage message, Set<String> seenUrls, List<Map<String, Object>> content) {
        for (ResponseOutputMessage.Content part : message.content()) {
            ResponseOutputText text = part.outputText().orElse(null);
            if (text != null) {
                content.add(textBlock(text.text()));
                content.addAll(WebSearchBlocks.of(newCitations(text, seenUrls)));
            } else if (part.refusal().isPresent()) {
                content.add(textBlock(part.refusal().get().refusal()));
            } else {
                log.warn("Dropping an unsupported Responses API content part");
            }
        }
    }

    /** The URL citations of a text that no text before it cited: (url, title). */
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

    private static Map<String, Object> toolUse(ResponseFunctionToolCall call, ToolNames names) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "tool_use");
        block.put("id", call.callId());
        block.put("name", names.toClient(call.name()));
        block.put("input", arguments(call.arguments()));
        return block;
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

    private static ResponseFailedException failure(Response response) {
        String message = response.error().map(error -> error.message()).orElse("The Responses API response has failed");
        return new ResponseFailedException(500, message);
    }

    private static Map<String, Object> usage(Response response) {
        Map<String, Object> usage = new LinkedHashMap<>();
        ResponseUsage tokens = response.usage().orElse(null);
        usage.put(
                "input_tokens", tokens == null ? 0 : ResponsesJson.count(tokens._inputTokens(), "usage.input_tokens"));
        usage.put(
                "output_tokens",
                tokens == null ? 0 : ResponsesJson.count(tokens._outputTokens(), "usage.output_tokens"));
        return usage;
    }

    private static Map<String, Object> textBlock(String text) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "text");
        block.put("text", text);
        return block;
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
