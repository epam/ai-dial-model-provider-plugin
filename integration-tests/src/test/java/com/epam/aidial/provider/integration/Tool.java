package com.epam.aidial.provider.integration;

/**
 * A function the model may call.
 *
 * @param schema the JSON Schema of its arguments
 */
record Tool(String name, String description, String schema) {

    /** The tool of the tool-calling test: it takes no argument, so that "What's the weather?" has nothing to ask. */
    static final Tool GET_WEATHER = new Tool(
            "get_weather",
            "Returns the current weather at the user's location.",
            """
            {"type": "object", "properties": {}}""");
}
