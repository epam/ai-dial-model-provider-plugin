package com.epam.aidial.provider.translation;

import com.anthropic.models.beta.messages.BetaMessageParam;
import com.anthropic.models.beta.messages.BetaThinkingConfigParam;
import com.anthropic.models.beta.messages.MessageCreateParams;
import com.epam.aidial.provider.api.InvalidRequestException;
import com.openai.errors.OpenAIInvalidDataException;
import com.openai.models.ReasoningEffort;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The {@code reasoning} of the Responses request: how much to think, and whether to say what was thought. */
final class Reasoning {

    private Reasoning() {}

    /** Empty when the request says nothing of thinking. */
    static Optional<Map<String, Object>> of(MessageCreateParams.Body request) {
        Optional<BetaThinkingConfigParam> thinking = request.thinking();
        Optional<String> effort = effort(request, thinking);
        Map<String, Object> reasoning = new LinkedHashMap<>();
        effort.ifPresent(e -> reasoning.put("effort", e));
        // Unlike Anthropic, the Responses API returns no reasoning text unless asked for a summary
        if (thinking.isPresent() && !effort.equals(Optional.of("none")) && !displayOmitted(thinking.get())) {
            reasoning.put("summary", "auto");
        }
        return reasoning.isEmpty() ? Optional.empty() : Optional.of(reasoning);
    }

    /**
     * Thinking disabled is {@code none}, whatever effort is asked. Otherwise the effort is the last visible system
     * turn's, else {@code output_config.effort}: token budgets and qualitative levels have no protocol-defined
     * conversion, so the target's default stays unless the effort is explicit.
     *
     * @throws InvalidRequestException for an effort the Responses API has no word for
     */
    private static Optional<String> effort(
            MessageCreateParams.Body request, Optional<BetaThinkingConfigParam> thinking) {
        if (thinking.filter(BetaThinkingConfigParam::isDisabled).isPresent()) {
            return Optional.of("none");
        }
        Optional<String> effort = systemTurnEffort(request).filter(e -> !e.isEmpty());
        if (effort.isEmpty()) {
            effort = AnthropicJson.effort(request).filter(e -> !e.isEmpty());
        }
        if (effort.isPresent() && !known(effort.get())) {
            throw new InvalidRequestException("Cannot convert Anthropic Messages API reasoning effort " + effort.get()
                    + " to an OpenAI reasoning effort");
        }
        return effort;
    }

    private static Optional<String> systemTurnEffort(MessageCreateParams.Body request) {
        List<BetaMessageParam> systemTurns = InputItems.visible(request.messages()).stream()
                .filter(message -> message.role().asString().equals("system"))
                .toList();
        if (systemTurns.isEmpty()) {
            return Optional.empty();
        }
        return systemTurns
                .getLast()
                .system()
                .flatMap(system -> system.outputConfig())
                .flatMap(config -> config.effort())
                .map(effort -> effort.asString());
    }

    /** The efforts the OpenAI SDK has constants for: the SDK follows the API. */
    private static boolean known(String effort) {
        try {
            ReasoningEffort.of(effort).known();
            return true;
        } catch (OpenAIInvalidDataException e) {
            return false;
        }
    }

    private static boolean displayOmitted(BetaThinkingConfigParam thinking) {
        Optional<String> display =
                switch (thinking.type().value()) {
                    case ENABLED -> thinking.asEnabled().display().map(d -> d.asString());
                    case ADAPTIVE -> thinking.asAdaptive().display().map(d -> d.asString());
                    case DISABLED, BETWEEN_TOOLS, _UNKNOWN -> Optional.empty();
                };
        return display.filter("omitted"::equals).isPresent();
    }
}
