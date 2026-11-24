package com.epam.aidial.provider.translation;

import com.anthropic.models.beta.messages.BetaJsonOutputFormat;
import com.anthropic.models.beta.messages.BetaTextBlockParam;
import com.anthropic.models.beta.messages.BetaToolUnion;
import com.anthropic.models.beta.messages.MessageCreateParams;
import com.epam.aidial.provider.api.InvalidRequestException;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFormatTextJsonSchemaConfig;
import com.openai.models.responses.ResponseIncludable;
import com.openai.models.responses.ResponseTextConfig;
import java.util.ArrayList;
import java.util.List;
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
    static Translation translate(MessageCreateParams.Body request, ToolNames names) {
        ResponseCreateParams.Builder body = ResponseCreateParams.builder();
        AnthropicJson.model(request).ifPresent(body::model);
        Dropped dropped = new Dropped();
        InputItems.Result input = new InputItems(new ContentParts(dropped), names, dropped).of(request.messages());
        body.input(ResponseCreateParams.Input.ofResponse(input.items()));
        String instructions = instructions(request);
        if (!instructions.isEmpty()) {
            body.instructions(instructions);
        }
        Tools tools = new Tools(names, dropped);
        List<BetaToolUnion> sentTools = request.tools().orElse(List.of());
        if (!sentTools.isEmpty()) {
            body.tools(tools.translate(sentTools));
        }
        tools.maxToolCalls(sentTools).ifPresent(max -> body.maxToolCalls(max));
        request.toolChoice().ifPresent(choice -> {
            tools.toolChoice(choice, sentTools).ifPresent(c -> body.toolChoice(c));
            Tools.parallelToolCalls(choice).ifPresent(parallel -> body.parallelToolCalls(parallel));
        });
        Reasoning.of(request).ifPresent(reasoning -> body.reasoning(reasoning));
        textConfig(request, dropped).ifPresent(text -> body.text(text));
        // The Messages API keeps no state between the requests
        body.store(false);
        // Replays the reasoning of the previous turns without stored state
        body.addInclude(ResponseIncludable.REASONING_ENCRYPTED_CONTENT);
        AnthropicJson.optional(request._maxTokens(), "max_tokens")
                .map(RequestTranslator::outputTokens)
                .ifPresent(tokens -> body.maxOutputTokens(tokens));
        AnthropicJson.number(request, "temperature").ifPresent(n -> body.temperature(n.doubleValue()));
        AnthropicJson.number(request, "top_p").ifPresent(n -> body.topP(n.doubleValue()));
        request.serviceTier().flatMap(RequestTranslator::serviceTier).ifPresent(tier -> body.serviceTier(tier));
        user(request).ifPresent(user -> user(body, user));
        droppedParameters(request, dropped);
        dropped.warn(log);
        return new Translation(
                body,
                new CachePaths(sentTools.size(), systemBlocks(request), !instructions.isEmpty(), input.itemOfBlock()));
    }

    /** The structured output: a JSON schema, which is all the Responses API takes of {@code output_config.format}. */
    private static Optional<ResponseTextConfig> textConfig(MessageCreateParams.Body request, Dropped dropped) {
        BetaJsonOutputFormat format =
                request.outputConfig().flatMap(config -> config.format()).orElse(null);
        if (format == null) {
            return Optional.empty();
        }
        String type = AnthropicJson.string(format._type()).orElse("(none)");
        if (!type.equals("json_schema")) {
            dropped.add("output_config.format type " + type);
            return Optional.empty();
        }
        if (format._schema().isMissing()
                || format.schema()._additionalProperties().isEmpty()) {
            dropped.add("output_config.format without a schema");
            return Optional.empty();
        }
        return Optional.of(ResponseTextConfig.builder()
                .format(ResponseFormatTextJsonSchemaConfig.builder()
                        .name("response")
                        .schema(ResponsesJson.fromAnthropic(
                                format.schema(), ResponseFormatTextJsonSchemaConfig.Schema.class))
                        .strict(true)
                        .build())
                .build());
    }

    /** The Responses request, and the paths of the cache between it and the Messages request it comes from. */
    record Translation(ResponseCreateParams.Builder request, CachePaths cachePaths) {}

    /** The blocks of {@code system} as Core counts them: a string is one. */
    private static int systemBlocks(MessageCreateParams.Body request) {
        return request.system()
                .map(system -> system.string().isPresent()
                        ? 1
                        : system.betaTextBlockParams().map(List::size).orElse(0))
                .orElse(0);
    }

    private static long outputTokens(long maxTokens) {
        return maxTokens > 0 && maxTokens < MIN_OUTPUT_TOKENS ? MIN_OUTPUT_TOKENS : maxTokens;
    }

    private static Optional<ResponseCreateParams.ServiceTier> serviceTier(MessageCreateParams.ServiceTier tier) {
        return switch (tier.value()) {
            case AUTO -> Optional.of(ResponseCreateParams.ServiceTier.AUTO);
            case STANDARD_ONLY -> Optional.of(ResponseCreateParams.ServiceTier.DEFAULT);
            case _UNKNOWN -> Optional.empty();
        };
    }

    /** The Messages API has a {@code user_id}, so the Responses API's {@code user}, which has been deprecated. */
    @SuppressWarnings("deprecation")
    private static void user(ResponseCreateParams.Builder body, String user) {
        body.user(user);
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
