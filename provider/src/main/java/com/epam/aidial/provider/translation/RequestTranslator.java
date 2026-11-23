package com.epam.aidial.provider.translation;

import com.anthropic.models.beta.messages.BetaContentBlockParam;
import com.anthropic.models.beta.messages.BetaMessageContentParam;
import com.anthropic.models.beta.messages.BetaMessageParam;
import com.anthropic.models.beta.messages.BetaTextBlockParam;
import com.anthropic.models.beta.messages.MessageCreateParams;
import com.epam.aidial.provider.api.InvalidRequestException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Messages API request as a Responses API request. Pure: no I/O, and the same request always gives the same body.
 * What it reads of the request it reads through the SDK's typed getters, which throw on a field of the wrong type.
 */
final class RequestTranslator {

    private static final Logger log = LoggerFactory.getLogger(RequestTranslator.class);

    /** The Responses API rejects a smaller output budget, e.g. the one-token budget of the Claude Code probes. */
    private static final long MIN_OUTPUT_TOKENS = 16;

    private static final int MAX_USER_LENGTH = 64;

    private RequestTranslator() {}

    /**
     * @throws InvalidRequestException when the request cannot be translated
     * @throws com.anthropic.errors.AnthropicInvalidDataException when a field it reads is missing or mistyped
     */
    static Map<String, Object> translate(MessageCreateParams.Body request, boolean stream) {
        warnDropped(request);
        Map<String, Object> body = new LinkedHashMap<>();
        AnthropicJson.model(request).ifPresent(model -> body.put("model", model));
        body.put("input", inputItems(request.messages()));
        String instructions = instructions(request);
        if (!instructions.isEmpty()) {
            body.put("instructions", instructions);
        }
        // The Messages API keeps no state between the requests
        body.put("store", false);
        // Replays the reasoning of the previous turns without stored state
        body.put("include", List.of("reasoning.encrypted_content"));
        body.put("stream", stream);
        AnthropicJson.optional(request._maxTokens(), "max_tokens")
                .map(RequestTranslator::outputTokens)
                .ifPresent(tokens -> body.put("max_output_tokens", tokens));
        AnthropicJson.number(request, "temperature").ifPresent(n -> body.put("temperature", n));
        AnthropicJson.number(request, "top_p").ifPresent(n -> body.put("top_p", n));
        request.serviceTier().map(tier -> serviceTier(tier.asString())).ifPresent(t -> body.put("service_tier", t));
        user(request).ifPresent(user -> body.put("user", user));
        return body;
    }

    private static long outputTokens(long maxTokens) {
        return maxTokens > 0 && maxTokens < MIN_OUTPUT_TOKENS ? MIN_OUTPUT_TOKENS : maxTokens;
    }

    private static String serviceTier(String tier) {
        return switch (tier) {
            case "auto" -> "auto";
            case "standard_only" -> "default";
            default -> null;
        };
    }

    private static Optional<String> user(MessageCreateParams.Body request) {
        Optional<String> user = request.metadata().flatMap(m -> m.userId());
        if (user.isPresent() && user.get().length() > MAX_USER_LENGTH) {
            // Truncated, the id would name another user
            log.debug(
                    "Dropping metadata.user_id: it's longer than the {} characters the Responses API accepts",
                    MAX_USER_LENGTH);
            return Optional.empty();
        }
        return user.filter(u -> !u.isEmpty());
    }

    private static void warnDropped(MessageCreateParams.Body request) {
        List<String> dropped = new ArrayList<>();
        if (request._additionalProperties().containsKey("top_k")) {
            dropped.add("top_k");
        }
        if (!request._stopSequences().isMissing()) {
            dropped.add("stop_sequences");
        }
        if (!request._compaction().isMissing()) {
            dropped.add("compaction");
        }
        if (!request._mcpServers().isMissing()) {
            dropped.add("mcp_servers");
        }
        if (!request._container().isMissing()) {
            dropped.add("container");
        }
        if (!request._inferenceGeo().isMissing()) {
            dropped.add("inference_geo");
        }
        if (!request._contextManagement().isMissing()) {
            dropped.add("context_management");
        }
        if (!dropped.isEmpty()) {
            log.warn(
                    "Ignoring Anthropic Messages API parameters that have no Responses API equivalent: {}.",
                    String.join(", ", dropped));
        }
    }

    private static String instructions(MessageCreateParams.Body request) {
        List<String> parts = new ArrayList<>();
        request.system().ifPresent(system -> {
            system.string().filter(text -> !text.isEmpty()).ifPresent(parts::add);
            system.betaTextBlockParams().ifPresent(blocks -> blocks.stream()
                    .map(BetaTextBlockParam::text)
                    .filter(text -> !text.isEmpty())
                    .forEach(parts::add));
        });
        return String.join("\n\n", parts);
    }

    private static List<Map<String, Object>> inputItems(List<BetaMessageParam> messages) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (BetaMessageParam message : messages) {
            String role = message.role().asString();
            BetaMessageContentParam content = message.content().orElse(null);
            switch (role) {
                case "user" -> userTurn(content, items);
                case "assistant" -> assistantTurn(content, items);
                default -> throw new InvalidRequestException("Unsupported message role: " + role);
            }
        }
        return items;
    }

    private static void userTurn(BetaMessageContentParam content, List<Map<String, Object>> items) {
        List<Map<String, Object>> parts = new ArrayList<>();
        for (String text : texts(content)) {
            parts.add(textPart(text));
        }
        if (!parts.isEmpty()) {
            items.add(message("user", parts));
        }
    }

    private static void assistantTurn(BetaMessageContentParam content, List<Map<String, Object>> items) {
        List<String> texts = texts(content);
        if (!texts.isEmpty()) {
            items.add(message("assistant", String.join("\n", texts)));
        }
    }

    /** The non-empty texts of a turn: its string, or its text blocks. Other blocks are dropped. */
    private static List<String> texts(BetaMessageContentParam content) {
        List<String> texts = new ArrayList<>();
        if (content == null) {
            return texts;
        }
        content.string().filter(text -> !text.isEmpty()).ifPresent(texts::add);
        for (BetaContentBlockParam block : content.contentBlockParams().orElse(List.of())) {
            Optional<BetaTextBlockParam> text = block.text();
            if (text.isPresent()) {
                if (!text.get().text().isEmpty()) {
                    texts.add(text.get().text());
                }
            } else {
                log.warn("Dropping unsupported content block: {}", AnthropicJson.type(AnthropicJson.raw(block)));
            }
        }
        return texts;
    }

    private static Map<String, Object> message(String role, Object content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "message");
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private static Map<String, Object> textPart(String text) {
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("type", "input_text");
        part.put("text", text);
        return part;
    }
}
