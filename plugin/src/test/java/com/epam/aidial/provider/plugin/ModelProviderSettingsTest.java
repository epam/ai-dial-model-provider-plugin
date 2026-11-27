package com.epam.aidial.provider.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.epam.aidial.provider.api.ConfigException;
import com.epam.aidial.provider.core.Settings;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModelProviderSettingsTest {

    private static ObjectNode entry() {
        return JsonNodeFactory.instance.objectNode().put("adapter", "openai");
    }

    @Test
    void adapterIsTheNameTheEntryServes() {
        assertEquals(
                Adapter.BEDROCK,
                ModelProviderSettings.of(entry().put("adapter", "bedrock"), Map.of())
                        .adapter());
        assertEquals("bedrock", Adapter.BEDROCK.value());
    }

    @Test
    void adapterIsRequiredAndKnown() {
        ConfigException missing = assertThrows(
                ConfigException.class, () -> ModelProviderSettings.of(JsonNodeFactory.instance.objectNode(), Map.of()));
        assertEquals("adapter", missing.setting());
        assertEquals("an entry needs an adapter (one of bedrock, openai, vertexai)", missing.getMessage());
        ConfigException unknown = assertThrows(
                ConfigException.class, () -> ModelProviderSettings.of(entry().put("adapter", "nope"), Map.of()));
        assertEquals("adapter", unknown.setting());
        assertEquals("must be one of bedrock, openai, vertexai", unknown.getMessage());
    }

    @Test
    void settingsAreTheEntryOverTheEnvironment() {
        ObjectNode entry = entry().put("HTTP_MAX_CONNECTIONS", 10)
                .putNull("HTTP_PROXY")
                .put("DIAL_URL", "entry")
                .putNull("NO_PROXY");
        entry.putObject("AWS_SESSION_TAGS").put("x", "y");
        Settings settings = ModelProviderSettings.of(
                        entry,
                        Map.of(
                                "HTTP_MAX_CONNECTIONS", "5",
                                "HTTP_POOL_TIMEOUT", "7",
                                "HTTP_PROXY", "env",
                                "DIAL_URL", "env",
                                "PATH", "no setting"))
                .settings();
        assertEquals(10, settings.httpMaxConnections());
        assertEquals(7, settings.httpPoolTimeout());
        assertEquals("env", settings.httpProxy(), "null is the same as absent");
        assertEquals("entry", settings.dialUrl());
        assertEquals(Map.of("x", "y"), settings.awsSessionTags());
    }

    @Test
    void entryHasNoSettingButTheOnesTheServiceReads() {
        ConfigException e = assertThrows(
                ConfigException.class, () -> ModelProviderSettings.of(entry().put("SOMETHING_NEW", "x"), Map.of()));
        assertEquals("SOMETHING_NEW", e.setting());
        assertEquals("SOMETHING_NEW is not a setting", e.getMessage());
    }

    @Test
    void settingsDoNotPrint() {
        String printed = ModelProviderSettings.of(entry().put("AWS_SECRET_ACCESS_KEY", "secret"), Map.of())
                .toString();
        assertFalse(printed.contains("secret"), printed);
    }
}
