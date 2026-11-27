package com.epam.aidial.provider.plugin;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Locale;

/** The adapters a model-provider entry serves, each below {@code /compat/<name>}. */
enum Adapter {
    @JsonProperty("openai")
    OPENAI,
    @JsonProperty("vertexai")
    VERTEXAI,
    @JsonProperty("bedrock")
    BEDROCK;

    /** The adapter's name in the settings and in the path. */
    String value() {
        return name().toLowerCase(Locale.ROOT);
    }
}
