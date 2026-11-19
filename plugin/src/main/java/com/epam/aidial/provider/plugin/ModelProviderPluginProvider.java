package com.epam.aidial.provider.plugin;

import com.epam.aidial.core.plugin.Plugin;
import com.epam.aidial.core.plugin.PluginProvider;
import com.epam.aidial.provider.ModelProviders;
import com.epam.aidial.provider.api.ConfigException;
import com.epam.aidial.provider.core.Config;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Vertx;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * A variable an entry's settings leave out comes from DIAL Core's environment, where credentials belong: DIAL Core
 * keeps an entry's settings as written.
 */
public final class ModelProviderPluginProvider implements PluginProvider {

    private static final String ADAPTER = "adapter";

    private final Map<String, String> environment;

    public ModelProviderPluginProvider() {
        this(System.getenv());
    }

    ModelProviderPluginProvider(Map<String, String> environment) {
        this.environment = environment;
    }

    @Override
    public String type() {
        return "model-provider";
    }

    @Override
    public List<Problem> validate(ObjectNode settings) {
        if (!ModelProviders.ADAPTERS.contains(adapter(settings))) {
            return List.of(new Problem(
                    ADAPTER, "must be one of " + String.join(", ", new TreeSet<>(ModelProviders.ADAPTERS))));
        }
        try {
            Config.from(variables(settings));
            return List.of();
        } catch (ConfigException e) {
            return List.of(new Problem(settings.has(e.setting()) ? e.setting() : "", e.getMessage()));
        }
    }

    @Override
    public Plugin create(ObjectNode settings, Vertx vertx) {
        return new ModelProviderPlugin(
                vertx, "/compat/" + adapter(settings), ModelProviders.create(vertx, Config.from(variables(settings))));
    }

    private static String adapter(ObjectNode settings) {
        JsonNode adapter = settings.get(ADAPTER);
        return adapter != null && adapter.isTextual() ? adapter.textValue() : "";
    }

    /** The environment, then the entry's settings as text: an object or array as its JSON, and null unsets. */
    private Map<String, String> variables(ObjectNode settings) {
        Map<String, String> variables = new HashMap<>(environment);
        for (Map.Entry<String, JsonNode> setting : settings.properties()) {
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
