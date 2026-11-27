package com.epam.aidial.provider.plugin;

import com.epam.aidial.core.plugin.Plugin;
import com.epam.aidial.core.plugin.PluginProvider;
import com.epam.aidial.core.plugin.PluginTarget;
import com.epam.aidial.provider.ModelProviders;
import com.epam.aidial.provider.api.ConfigException;
import com.epam.aidial.provider.core.Config;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Vertx;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    public String id() {
        return "model-provider";
    }

    @Override
    public String description() {
        return "Forwards model requests to the LLM APIs of the clouds: Azure OpenAI and OpenAI, Anthropic on Vertex AI"
                + " and on Amazon Bedrock.";
    }

    @Override
    public Set<PluginTarget> targets() {
        return Set.of(PluginTarget.MODEL);
    }

    @Override
    public ObjectNode settingsSchema() {
        return Schemas.of(ModelProviderSettings.class, Variable.class);
    }

    /** The schema has accepted the settings: what is left is the variables' own checks. */
    @Override
    public List<Problem> validate(ObjectNode node) {
        try {
            Config.from(ModelProviderSettings.of(node).variables(environment));
            return List.of();
        } catch (ConfigException e) {
            return List.of(new Problem(node.has(e.setting()) ? e.setting() : "", e.getMessage()));
        }
    }

    @Override
    public Plugin create(ObjectNode node, Vertx vertx) {
        ModelProviderSettings settings = ModelProviderSettings.of(node);
        Config config = Config.from(settings.variables(environment));
        return new ModelProviderPlugin(
                vertx, "/compat/" + settings.adapter().value(), ModelProviders.create(vertx, config));
    }
}
