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
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * A variable an entry's settings leave out comes from DIAL Core's environment, where credentials belong: DIAL Core
 * keeps an entry's settings as written.
 */
public final class ModelProviderPluginProvider implements PluginProvider {

    private static final String ADAPTER = "adapter";
    private static final String TRANSLATOR = "translator";

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
        Optional<Problem> invalid = entry(settings);
        if (invalid.isPresent()) {
            return List.of(invalid.get());
        }
        try {
            Config.from(settings.variables(environment));
            return List.of();
        } catch (ConfigException e) {
            return List.of(new Problem(node.has(e.setting()) ? e.setting() : "", e.getMessage()));
        }
    }

    /** An entry is an adapter or a translator, and one of the names this plugin has. */
    private static Optional<Problem> entry(PluginSettings settings) {
        if (settings.hasAdapter() && settings.hasTranslator()) {
            return Optional.of(new Problem(TRANSLATOR, "an entry has an adapter or a translator, not both"));
        }
        if (settings.hasTranslator()) {
            return ModelProviders.TRANSLATORS.contains(settings.translator())
                    ? Optional.empty()
                    : Optional.of(new Problem(TRANSLATOR, "must be one of " + names(ModelProviders.TRANSLATORS)));
        }
        if (settings.hasAdapter()) {
            return ModelProviders.ADAPTERS.contains(settings.adapter())
                    ? Optional.empty()
                    : Optional.of(new Problem(ADAPTER, "must be one of " + names(ModelProviders.ADAPTERS)));
        }
        return Optional.of(new Problem(
                ADAPTER,
                "an entry needs an adapter (one of " + names(ModelProviders.ADAPTERS) + ") or a translator (one of "
                        + names(ModelProviders.TRANSLATORS) + ")"));
    }

    private static String names(Set<String> names) {
        return String.join(", ", new TreeSet<>(names));
    }

    @Override
    public Plugin create(ObjectNode node, Vertx vertx) {
        PluginSettings settings = PluginSettings.of(node);
        Config config = Config.from(settings.variables(environment));
        if (settings.hasTranslator()) {
            return new ModelProviderPlugin(
                    vertx, "/" + settings.translator(), ModelProviders.createTranslator(vertx, config));
        }
        return new ModelProviderPlugin(vertx, "/compat/" + settings.adapter(), ModelProviders.create(vertx, config));
    }
}
