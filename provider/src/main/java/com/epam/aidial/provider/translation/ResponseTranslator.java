package com.epam.aidial.provider.translation;

import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseOutputText;
import com.openai.models.responses.ResponseUsage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    static Map<String, Object> message(Response response, String model) {
        if (response.status().map(status -> status.asString().equals("failed")).orElse(false)) {
            throw failure(response);
        }
        List<Map<String, Object>> content = new ArrayList<>();
        for (ResponseOutputItem item : response.output()) {
            ResponseOutputMessage message = item.message().orElse(null);
            if (message == null) {
                log.warn("Dropping an unsupported Responses API output item");
                continue;
            }
            for (ResponseOutputMessage.Content part : message.content()) {
                ResponseOutputText text = part.outputText().orElse(null);
                if (text != null) {
                    content.add(textBlock(text.text()));
                } else if (part.refusal().isPresent()) {
                    content.add(textBlock(part.refusal().get().refusal()));
                } else {
                    log.warn("Dropping an unsupported Responses API content part");
                }
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
        message.put("stop_reason", "end_turn");
        message.put("usage", usage(response));
        return message;
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
