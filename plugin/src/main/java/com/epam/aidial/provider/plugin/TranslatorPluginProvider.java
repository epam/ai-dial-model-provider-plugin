package com.epam.aidial.provider.plugin;

import com.epam.aidial.core.plugin.Plugin;
import com.epam.aidial.core.plugin.PluginProvider;
import com.epam.aidial.provider.ModelProviders;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Vertx;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/**
 * The translators of {@link ModelProviders#TRANSLATORS}, each at {@code /<translator>}: DIAL Core's translator
 * contract, where the Responses call goes to DIAL Core itself, through the host of the request.
 */
public final class TranslatorPluginProvider implements PluginProvider {

    private static final String TRANSLATOR = "translator";

    @Override
    public String type() {
        return "translator";
    }

    @Override
    public List<Problem> validate(ObjectNode node) {
        TranslatorSettings settings;
        try {
            settings = TranslatorSettings.of(node);
        } catch (IllegalArgumentException e) {
            return List.of(problem(e.getCause()));
        }
        return settings.translator() != null && ModelProviders.TRANSLATORS.contains(settings.translator())
                ? List.of()
                : List.of(new Problem(TRANSLATOR, "must be one of " + translators()));
    }

    private static Problem problem(Throwable cause) {
        return switch (cause) {
            case UnrecognizedPropertyException unknown ->
                new Problem(unknown.getPropertyName(), "is not a setting of a translator");
            case MismatchedInputException mismatched ->
                new Problem(
                        Optional.ofNullable(mismatched.getPath()).stream()
                                .flatMap(List::stream)
                                .map(reference -> reference.getFieldName())
                                .findFirst()
                                .orElse(""),
                        "must be text");
            default -> new Problem("", String.valueOf(cause.getMessage()));
        };
    }

    private static String translators() {
        return String.join(", ", new TreeSet<>(ModelProviders.TRANSLATORS));
    }

    @Override
    public Plugin create(ObjectNode node, Vertx vertx) {
        return new ModelProviderPlugin(
                vertx, "/" + TranslatorSettings.of(node).translator(), ModelProviders.createTranslator());
    }
}
