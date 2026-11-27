package com.epam.aidial.provider.plugin;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * A translator entry's settings: the direction of the translation, which says what the translator accepts and what it
 * converts to. It is required, and no other setting is accepted, as a setting the translator does not read is a mistake
 * in the entry.
 */
@JsonClassDescription("A translator: the direction of the translation.")
record TranslatorSettings(
        @JsonProperty(value = "direction", required = true)
                @JsonPropertyDescription("The API the translator accepts and the API it calls.")
                Direction direction) {

    /** The APIs a translator converts between, from the one it accepts to the one it calls. */
    enum Direction {
        @JsonProperty("anthropicMessagesToOpenaiResponses")
        ANTHROPIC_MESSAGES_TO_OPENAI_RESPONSES
    }
}
