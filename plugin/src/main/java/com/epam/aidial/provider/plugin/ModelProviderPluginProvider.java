package com.epam.aidial.provider.plugin;

import com.epam.aidial.core.plugin.Plugin;
import com.epam.aidial.core.plugin.PluginProvider;
import com.epam.aidial.provider.ModelProviders;
import com.epam.aidial.provider.api.ConfigException;
import com.epam.aidial.provider.core.Adapter;
import com.epam.aidial.provider.core.Config;
import com.epam.aidial.provider.core.Settings;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Vertx;
import java.util.List;
import java.util.Map;

/**
 * The adapters of {@link Adapter}, each below {@code /compat/<adapter>}. A variable an entry's settings
 * leave out comes from DIAL Core's environment, where credentials belong: DIAL Core keeps an entry's settings as
 * written.
 */
public final class ModelProviderPluginProvider implements PluginProvider {

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

    public String description() {
        return "Forwards model requests to the LLM APIs of the clouds: Azure OpenAI and OpenAI, Anthropic on Vertex AI"
                + " and on Amazon Bedrock.";
    }

    public ObjectNode settingsSchema() {
        return Schemas.of(Settings.class);
    }

    /** DIAL Core checks no schema before the plugin API of 0.51.0: the entry is read here, as `create` would. */
    @Override
    public List<Problem> validate(ObjectNode node) {
        try {
            Config.from(Settings.of(node, environment));
            return List.of();
        } catch (ConfigException e) {
            boolean entry = Settings.ADAPTER.equals(e.setting()) || node.hasNonNull(e.setting());
            return List.of(new Problem(entry ? e.setting() : "", e.getMessage()));
        }
    }

    @Override
    public Plugin create(ObjectNode node, Vertx vertx) {
        Settings settings = Settings.of(node, environment);
        return new ModelProviderPlugin(
                vertx, "/compat/" + settings.adapter().value(), ModelProviders.create(vertx, Config.from(settings)));
    }
}
