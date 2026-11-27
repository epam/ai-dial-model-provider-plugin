package com.epam.aidial.provider.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
                ModelProviderSettings.of(entry().put("adapter", "bedrock")).adapter());
        assertEquals("bedrock", Adapter.BEDROCK.value());
    }

    @Test
    void variablesAreTheEnvironmentThenTheEntryWithoutTheAdapter() {
        ObjectNode entry = entry().put("HTTP_POOL_TIMEOUT", "entry")
                .putNull("HTTP_PROXY")
                .put("HTTP_MAX_CONNECTIONS", 10)
                .put("AWS_BEDROCK_BASE_URL", 1.5)
                .put("SSE_HEARTBEAT_INTERVAL", true)
                .put("http_proxy", "lower")
                .putNull("SOMETHING_NEW");
        entry.putObject("AWS_SESSION_TAGS").put("x", "y");
        assertEquals(
                Map.of(
                        "HTTP_POOL_TIMEOUT", "entry",
                        "HTTP_MAX_CONNECTIONS", "10",
                        "AWS_BEDROCK_BASE_URL", "1.5",
                        "SSE_HEARTBEAT_INTERVAL", "true",
                        "http_proxy", "lower",
                        "AWS_SESSION_TAGS", "{\"x\":\"y\"}",
                        "DIAL_URL", "env"),
                ModelProviderSettings.of(entry)
                        .variables(Map.of(
                                "HTTP_POOL_TIMEOUT",
                                "env",
                                "HTTP_PROXY",
                                "env",
                                "DIAL_URL",
                                "env",
                                "SOMETHING_NEW",
                                "env")));
    }

    @Test
    void variablesDoNotPrint() {
        assertEquals("***", new Variable("secret").toString());
        String printed = ModelProviderSettings.of(
                        entry().put("AWS_SECRET_ACCESS_KEY", "secret").put("OTHER", "secret"))
                .toString();
        assertFalse(printed.contains("secret"), printed);
    }
}
