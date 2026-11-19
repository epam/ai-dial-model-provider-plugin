package com.epam.aidial.provider.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/** An API the service passes through: how a question is put to it, and how its answer is read. */
enum Api {
    RESPONSES("""
            {"model": %s, "input": %s, "max_output_tokens": 4000%s}""") {
        @Override
        String toolFields(Tool tool) {
            return """
                    , "instructions": %s,
                      "tools": [{"type": "function", "name": %s, "description": %s, "parameters": %s}]"""
                    .formatted(quote(CALL_AT_ONCE), quote(tool.name()), quote(tool.description()), tool.schema());
        }

        @Override
        Answer read(JsonNode answer) {
            StringBuilder text = new StringBuilder();
            List<String> calls = new ArrayList<>();
            for (JsonNode item : answer.path("output")) {
                if (item.path("type").asText().equals("function_call")) {
                    calls.add(item.path("name").asText());
                }
                for (JsonNode part : item.path("content")) {
                    text.append(part.path("text").asText());
                }
            }
            return new Answer(text.toString(), calls);
        }
    },
    MESSAGES("""
            {"model": %s, "max_tokens": 4000, "messages": [{"role": "user", "content": %s}]%s}""") {
        @Override
        String toolFields(Tool tool) {
            return """
                    , "system": %s,
                      "tools": [{"name": %s, "description": %s, "input_schema": %s}]"""
                    .formatted(quote(CALL_AT_ONCE), quote(tool.name()), quote(tool.description()), tool.schema());
        }

        @Override
        Answer read(JsonNode answer) {
            StringBuilder text = new StringBuilder();
            List<String> calls = new ArrayList<>();
            for (JsonNode block : answer.path("content")) {
                switch (block.path("type").asText()) {
                    case "text" -> text.append(block.path("text").asText());
                    case "tool_use" -> calls.add(block.path("name").asText());
                    default -> {
                        // thinking blocks and the like say nothing the tests check
                    }
                }
            }
            return new Answer(text.toString(), calls);
        }
    };

    private static final ObjectMapper JSON = new ObjectMapper();
    // The newer models refuse a forced tool choice, and ask "which city?" of "What's the weather?" unless told not to.
    private static final String CALL_AT_ONCE =
            "Call the tools you are given at once, with the arguments you have. Never ask the user for more.";

    private final String template;

    Api(String template) {
        this.template = template;
    }

    /** The request body asking the question; the model may call the tool, when there is one. */
    String request(String model, String question, Tool tool) {
        return template.formatted(quote(model), quote(question), tool == null ? "" : toolFields(tool));
    }

    /** The members of the request that offer the tool, and the instruction to call it. */
    abstract String toolFields(Tool tool);

    abstract Answer read(JsonNode answer);

    private static String quote(String text) {
        try {
            return JSON.writeValueAsString(text);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
