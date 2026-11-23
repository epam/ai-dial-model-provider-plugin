package com.epam.aidial.provider.translation;

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
        Map<String, Object> body = new LinkedHashMap<>();
        AnthropicJson.model(request).ifPresent(model -> body.put("model", model));
        Dropped dropped = new Dropped();
        body.put("input", new InputItems(new ContentParts(dropped), dropped).of(request.messages()));
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
        droppedParameters(request, dropped);
        dropped.warn(log);
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

    private static void droppedParameters(MessageCreateParams.Body request, Dropped dropped) {
        if (request._additionalProperties().containsKey("top_k")) {
            dropped.add("parameter top_k");
        }
        if (!request._stopSequences().isMissing()) {
            dropped.add("parameter stop_sequences");
        }
        if (!request._compaction().isMissing()) {
            dropped.add("parameter compaction");
        }
        if (!request._mcpServers().isMissing()) {
            dropped.add("parameter mcp_servers");
        }
        if (!request._container().isMissing()) {
            dropped.add("parameter container");
        }
        if (!request._inferenceGeo().isMissing()) {
            dropped.add("parameter inference_geo");
        }
        if (!request._contextManagement().isMissing()) {
            dropped.add("parameter context_management");
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
}
