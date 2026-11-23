package com.epam.aidial.provider.plugin;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A plugin entry's settings as Jackson binds them: the keys the plugin reads itself, typed, and every other key as a
 * variable of the service's own configuration ({@link #variables}).
 */
final class PluginSettings {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private String adapter = "";
    private final Map<String, JsonNode> others = new LinkedHashMap<>();

    static PluginSettings of(ObjectNode settings) {
        return MAPPER.convertValue(settings, PluginSettings.class);
    }

    /** The name of the adapter the entry serves; empty if the setting is missing or is not text. */
    String adapter() {
        return adapter;
    }

    @JsonSetter("adapter")
    void adapter(JsonNode value) {
        adapter = value.isTextual() ? value.textValue() : "";
    }

    @JsonAnySetter
    void other(String name, JsonNode value) {
        others.put(name, value);
    }

    /** The environment, then the entry's settings as text: an object or array as its JSON, and null unsets. */
    Map<String, String> variables(Map<String, String> environment) {
        Map<String, String> variables = new HashMap<>(environment);
        for (Map.Entry<String, JsonNode> setting : others.entrySet()) {
            JsonNode value = setting.getValue();
            if (value.isNull()) {
                variables.remove(setting.getKey());
            } else {
                variables.put(setting.getKey(), value.isValueNode() ? value.asText() : value.toString());
            }
        }
        return variables;
    }
}
