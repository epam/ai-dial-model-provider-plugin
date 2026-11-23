package com.epam.aidial.provider.plugin;

import com.epam.aidial.core.plugin.Plugin;
import com.epam.aidial.core.plugin.PluginProvider;
import com.epam.aidial.provider.ModelProviders;
import com.epam.aidial.provider.api.ConfigException;
import com.epam.aidial.provider.core.Config;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Vertx;
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
    public List<Problem> validate(ObjectNode node) {
        PluginSettings settings = PluginSettings.of(node);
        if (!ModelProviders.ADAPTERS.contains(settings.adapter())) {
            return List.of(new Problem(
                    ADAPTER, "must be one of " + String.join(", ", new TreeSet<>(ModelProviders.ADAPTERS))));
        }
        try {
            Config.from(settings.variables(environment));
            return List.of();
        } catch (ConfigException e) {
            return List.of(new Problem(node.has(e.setting()) ? e.setting() : "", e.getMessage()));
        }
    }

    @Override
    public Plugin create(ObjectNode node, Vertx vertx) {
        PluginSettings settings = PluginSettings.of(node);
        return new ModelProviderPlugin(
                vertx,
                "/compat/" + settings.adapter(),
                ModelProviders.create(vertx, Config.from(settings.variables(environment))));
    }
}
