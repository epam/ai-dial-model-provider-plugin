package com.epam.aidial.provider.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** An HTTP answer of the service. */
record Reply(int status, String body) {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_DETAIL = 300;

    boolean ok() {
        return status == 200;
    }

    /** The body as JSON; a missing node for a body that is none. */
    JsonNode json() {
        try {
            return JSON.readTree(body);
        } catch (JsonProcessingException e) {
            return JSON.missingNode();
        }
    }

    /** The reason of a refusal, from where the APIs put it ({@code error.message}, {@code message}), else the body. */
    String reason() {
        JsonNode json = json();
        for (JsonNode message : new JsonNode[] {json.path("error").path("message"), json.path("message")}) {
            if (message.isTextual()) {
                return clip(message.asText());
            }
        }
        return clip(body);
    }

    private static String clip(String text) {
        String line = text.replaceAll("\\s+", " ").strip();
        return line.length() <= MAX_DETAIL ? line : line.substring(0, MAX_DETAIL) + "...";
    }
}
