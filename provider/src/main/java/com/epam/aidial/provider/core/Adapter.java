package com.epam.aidial.provider.core;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Locale;

/** The adapters of the service, each below {@code /compat/<name>}. */
public enum Adapter {
    @JsonProperty("openai")
    OPENAI,
    @JsonProperty("vertexai")
    VERTEXAI,
    @JsonProperty("bedrock")
    BEDROCK;

    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }
}
