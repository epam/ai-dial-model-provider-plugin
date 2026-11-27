package com.epam.aidial.provider.plugin;

import com.epam.aidial.core.plugin.Plugin;
import com.epam.aidial.core.plugin.PluginProvider;
import com.epam.aidial.provider.ModelProviders;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Vertx;
import java.util.List;

/**
 * DIAL Core's translator contract, served in process: the Messages API for a deployment that serves the Responses API,
 * which the plugin calls through the host of the request instead of over HTTP. The translator is told by the direction
 * of its settings ({@link TranslatorSettings}).
 */
public final class TranslatorPluginProvider implements PluginProvider {

    @Override
    public String type() {
        return "translator";
    }

    public String description() {
        return "Translates the Anthropic Messages API to the OpenAI Responses API.";
    }

    public ObjectNode settingsSchema() {
        return Schemas.of(TranslatorSettings.class);
    }

    /** The schema checks the settings, and the translator reads none. */
    @Override
    public List<Problem> validate(ObjectNode node) {
        return List.of();
    }

    @Override
    public Plugin create(ObjectNode node, Vertx vertx) {
        return new ModelProviderPlugin(vertx, ModelProviders.TRANSLATOR_PREFIX, ModelProviders.createTranslator());
    }
}
