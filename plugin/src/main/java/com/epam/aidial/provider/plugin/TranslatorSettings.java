package com.epam.aidial.provider.plugin;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jspecify.annotations.Nullable;

/**
 * A translator entry's settings: nothing but the translator it serves. Any other setting is refused, as a setting the
 * translator does not read is a mistake in the entry.
 *
 * @param translator the translator's name; null if the entry has none
 */
record TranslatorSettings(@JsonProperty("translator") @Nullable String translator) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * @throws IllegalArgumentException if the settings do not bind: the cause is Jackson's, which names the setting
     */
    static TranslatorSettings of(ObjectNode settings) {
        return MAPPER.convertValue(settings, TranslatorSettings.class);
    }
}
