package com.epam.aidial.provider.plugin;

import com.epam.aidial.core.plugin.Plugin;
import com.epam.aidial.core.plugin.PluginProvider;
import com.epam.aidial.core.plugin.PluginTarget;
import com.epam.aidial.provider.ModelProviders;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Vertx;
import java.util.Set;

/**
 * DIAL Core's translator contract, served in process: the Messages API for a deployment that serves the Responses API,
 * which the plugin calls through the host of the request instead of over HTTP. The translator is told by the direction
 * of its settings ({@link TranslatorSettings}).
 */
public final class TranslatorPluginProvider implements PluginProvider {

    @Override
    public String id() {
        return "translator";
    }

    @Override
    public String description() {
        return "Translates the Anthropic Messages API to the OpenAI Responses API.";
    }

    @Override
    public Set<PluginTarget> targets() {
        return Set.of(PluginTarget.TRANSLATOR);
    }

    @Override
    public ObjectNode settingsSchema() {
        return Schemas.of(TranslatorSettings.class, Void.class);
    }

    @Override
    public Plugin create(ObjectNode node, Vertx vertx) {
        return new ModelProviderPlugin(vertx, ModelProviders.TRANSLATOR_PREFIX, ModelProviders.createTranslator());
    }
}
