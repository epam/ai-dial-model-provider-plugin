package com.epam.aidial.provider.plugin;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A plugin entry's settings as Jackson binds them: the keys the plugin reads itself ({@code adapter} and {@code
 * translator}: an entry has one of them), and every other key as a variable of the service's own configuration ({@link
 * #variables}).
 */
final class PluginSettings {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private @Nullable JsonNode adapter;
    private @Nullable JsonNode translator;
    private final Map<String, JsonNode> others = new LinkedHashMap<>();

    static PluginSettings of(ObjectNode settings) {
        return MAPPER.convertValue(settings, PluginSettings.class);
    }

    /** Whether the entry serves an adapter: it has the setting (null is none). */
    boolean hasAdapter() {
        return adapter != null && !adapter.isNull();
    }

    /** The name of the adapter the entry serves; empty if the setting is missing or is not text. */
    String adapter() {
        return text(adapter);
    }

    @JsonSetter("adapter")
    void adapter(JsonNode value) {
        adapter = value;
    }

    /** Whether the entry is a translator: it has the setting (null is none). */
    boolean hasTranslator() {
        return translator != null && !translator.isNull();
    }

    /** The name of the translator the entry is; empty if the setting is missing or is not text. */
    String translator() {
        return text(translator);
    }

    @JsonSetter("translator")
    void translator(JsonNode value) {
        translator = value;
    }

    private static String text(@Nullable JsonNode value) {
        return value != null && value.isTextual() ? value.textValue() : "";
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
