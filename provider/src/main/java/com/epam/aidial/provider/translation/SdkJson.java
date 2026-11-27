package com.epam.aidial.provider.translation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;

/** What {@link AnthropicJson} and {@link ResponsesJson} share: the models of both SDKs are Jackson's. */
final class SdkJson {

    private SdkJson() {}

    static String describe(IOException e) {
        return e instanceof JsonProcessingException p ? p.getOriginalMessage() : String.valueOf(e.getMessage());
    }

    /** A model of an SDK as JSON, in the order and spelling its API uses. */
    static String write(JsonMapper mapper, Object model) {
        try {
            return mapper.writeValueAsString(model);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot write " + model.getClass().getSimpleName(), e);
        }
    }
}
