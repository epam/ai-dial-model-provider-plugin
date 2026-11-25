package com.epam.aidial.provider.translation;

import com.anthropic.models.beta.messages.BetaMessageParam;
import com.anthropic.models.beta.messages.BetaThinkingConfigParam;
import com.anthropic.models.beta.messages.MessageCreateParams;
import com.epam.aidial.provider.api.InvalidRequestException;
import com.openai.errors.OpenAIInvalidDataException;
import com.openai.models.ReasoningEffort;
import java.util.List;
import java.util.Optional;

final class Reasoning {

    private Reasoning() {}

    static Optional<com.openai.models.Reasoning> of(MessageCreateParams.Body request) {
        Optional<BetaThinkingConfigParam> thinking = request.thinking();
        Optional<ReasoningEffort> effort = effort(request, thinking);
        com.openai.models.Reasoning.Builder reasoning = com.openai.models.Reasoning.builder();
        effort.ifPresent(reasoning::effort);
        // Unlike Anthropic, the Responses API returns no reasoning text unless asked for a summary
        boolean summary = thinking.isPresent()
                && !effort.equals(Optional.of(ReasoningEffort.NONE))
                && !displayOmitted(thinking.get());
        if (summary) {
            reasoning.summary(com.openai.models.Reasoning.Summary.AUTO);
        }
        return effort.isPresent() || summary ? Optional.of(reasoning.build()) : Optional.empty();
    }

    /**
     * Thinking disabled is {@code none}, whatever effort is asked. Otherwise the effort is the last visible system
     * turn's, else {@code output_config.effort}: token budgets and qualitative levels have no protocol-defined
     * conversion, so the target's default stays unless the effort is explicit.
     *
     * @throws InvalidRequestException for an effort the Responses API has no word for
     */
    private static Optional<ReasoningEffort> effort(
            MessageCreateParams.Body request, Optional<BetaThinkingConfigParam> thinking) {
        if (thinking.filter(BetaThinkingConfigParam::isDisabled).isPresent()) {
            return Optional.of(ReasoningEffort.NONE);
        }
        Optional<String> effort = systemTurnEffort(request).filter(e -> !e.isEmpty());
        if (effort.isEmpty()) {
            effort = AnthropicJson.effort(request).filter(e -> !e.isEmpty());
        }
        return effort.map(Reasoning::openAiEffort);
    }

    private static Optional<String> systemTurnEffort(MessageCreateParams.Body request) {
        List<BetaMessageParam> systemTurns = InputItems.visible(request.messages()).stream()
                .filter(message -> message.role().value() == BetaMessageParam.Role.Value.SYSTEM)
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
    private static ReasoningEffort openAiEffort(String effort) {
        ReasoningEffort openAi = ReasoningEffort.of(effort);
        try {
            openAi.known();
        } catch (OpenAIInvalidDataException e) {
            throw new InvalidRequestException("Cannot convert Anthropic Messages API reasoning effort " + effort
                    + " to an OpenAI reasoning effort");
        }
        return openAi;
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
