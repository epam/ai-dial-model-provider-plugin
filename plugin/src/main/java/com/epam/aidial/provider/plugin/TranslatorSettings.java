package com.epam.aidial.provider.plugin;

import com.epam.aidial.core.plugin.PluginProvider.Problem;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidNullException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A translator entry's settings: the direction of the translation, which says what the translator accepts and what it
 * converts to. It is required, and no other setting is accepted, as a setting the translator does not read is a mistake
 * in the entry.
 */
record TranslatorSettings(@JsonProperty("direction") @JsonSetter(nulls = Nulls.FAIL) Direction direction) {

    /** The APIs a translator converts between, from the one it accepts to the one it calls. */
    enum Direction {
        ANTHROPIC_MESSAGES_TO_OPENAI_RESPONSES("anthropicMessagesToOpenaiResponses");

        @JsonValue
        private final String value;

        Direction(String value) {
            this.value = value;
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** What is wrong with the entry's settings, the first of them; empty if they are a translator's. */
    static List<Problem> problems(ObjectNode settings) {
        try {
            MAPPER.convertValue(settings, TranslatorSettings.class);
            return List.of();
        } catch (IllegalArgumentException e) {
            return List.of(problem(e.getCause()));
        }
    }

    private static Problem problem(Throwable cause) {
        return switch (cause) {
            case UnrecognizedPropertyException unknown ->
                new Problem(unknown.getPropertyName(), "is not a setting of a translator");
            case InvalidNullException missing ->
                new Problem(missing.getPropertyName().getSimpleName(), "is required");
            case MismatchedInputException mismatched ->
                new Problem(
                        mismatched.getPath().isEmpty()
                                ? ""
                                : mismatched.getPath().getFirst().getFieldName(),
                        "must be one of " + directions());
            default -> new Problem("", String.valueOf(cause.getMessage()));
        };
    }

    private static String directions() {
        return Arrays.stream(Direction.values())
                .map(direction -> direction.value)
                .collect(Collectors.joining(", "));
    }
}
