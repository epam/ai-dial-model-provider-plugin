package com.epam.aidial.provider.plugin;

import com.epam.aidial.provider.api.ConfigException;
import com.epam.aidial.provider.core.Settings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A model-provider entry's settings: the {@code adapter} the entry serves, and the {@link Settings} of the service
 * itself. A setting the entry leaves out, or sets to {@code null}, comes from DIAL Core's environment, where
 * credentials belong.
 */
record ModelProviderSettings(Adapter adapter, Settings settings) {

    static final String ADAPTER = "adapter";

    /**
     * @throws ConfigException for an adapter that is missing or unknown, for a name that is no setting, and for the
     *     first setting that is refused
     */
    static ModelProviderSettings of(ObjectNode entry, Map<String, String> environment) {
        ObjectNode settings = entry.deepCopy();
        settings.remove(ADAPTER);
        return new ModelProviderSettings(adapter(entry.get(ADAPTER)), Settings.of(settings, environment));
    }

    private static Adapter adapter(JsonNode name) {
        if (name == null || name.isNull()) {
            throw new ConfigException(ADAPTER, "an entry needs an adapter (one of " + adapters() + ")");
        }
        return Arrays.stream(Adapter.values())
                .filter(adapter -> name.isTextual() && adapter.value().equals(name.asText()))
                .findFirst()
                .orElseThrow(() -> new ConfigException(ADAPTER, "must be one of " + adapters()));
    }

    private static String adapters() {
        return Arrays.stream(Adapter.values()).map(Adapter::value).sorted().collect(Collectors.joining(", "));
    }
}
