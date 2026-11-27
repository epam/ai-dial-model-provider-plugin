package com.epam.aidial.provider.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.epam.aidial.provider.api.ConfigException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SettingsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ObjectNode entry(String json) throws Exception {
        ObjectNode entry = (ObjectNode) MAPPER.readTree(json);
        if (!entry.has("adapter")) {
            entry.put("adapter", "openai");
        }
        return entry;
    }

    private static ConfigException refused(String json) throws Exception {
        return assertThrows(ConfigException.class, () -> Settings.of(entry(json), Map.of()));
    }

    @Test
    void environmentIsTextOfTheTypesAndOtherNamesAreIgnored() {
        Settings settings = Settings.from(Map.of(
                "HTTP_MAX_CONNECTIONS", "64",
                "DIAL_URL", "https://dial.test",
                "AWS_SESSION_TAGS", "{\"a\":\"Bedrock.modelId\"}",
                "PATH", "/bin",
                "PORT", "80"));
        assertEquals(64, settings.httpMaxConnections());
        assertEquals("https://dial.test", settings.dialUrl());
        assertEquals(Map.of("a", "Bedrock.modelId"), settings.awsSessionTags());
        assertNull(settings.httpPoolTimeout());
    }

    @Test
    void entryNeedsAnAdapter() {
        ConfigException e = assertThrows(
                ConfigException.class, () -> Settings.of(MAPPER.createObjectNode(), Map.of("adapter", "openai")));
        assertEquals("adapter", e.setting());
        assertEquals("adapter is invalid", e.getMessage());
    }

    @Test
    void adapterIsOfTheEntryAndNotOfTheEnvironment() throws Exception {
        assertEquals(
                Adapter.BEDROCK,
                Settings.of(entry("{\"adapter\": \"bedrock\"}"), Map.of()).adapter());
        assertEquals(Adapter.OPENAI, Settings.from(Map.of("adapter", "bedrock")).adapter());
        assertEquals(
                Adapter.OPENAI,
                Settings.of(entry("{}"), Map.of("adapter", "bedrock")).adapter());
    }

    @Test
    void entryIsJsonOfTheTypes() throws Exception {
        Settings settings = Settings.of(
                entry("{\"HTTP_MAX_CONNECTIONS\": 64, \"DIAL_URL\": \"u\", \"AWS_SESSION_TAGS\": {\"a\": \"b\"}}"),
                Map.of());
        assertEquals(64, settings.httpMaxConnections());
        assertEquals("u", settings.dialUrl());
        assertEquals(Map.of("a", "b"), settings.awsSessionTags());
    }

    /** Reading is lax: text is an integer, and a number or a boolean is the text of a string. */
    @Test
    void entryMayGiveTheTextOfTheEnvironment() throws Exception {
        Settings settings = Settings.of(
                entry(
                        """
                        {"HTTP_MAX_CONNECTIONS": "64", "SSE_HEARTBEAT_INTERVAL": "15", "DIAL_URL": 1,
                         "NO_PROXY": true, "AWS_SESSION_TAGS": {"a": "b"}}"""),
                Map.of());
        assertEquals(64, settings.httpMaxConnections());
        assertEquals(15, settings.sseHeartbeatInterval());
        assertEquals("1", settings.dialUrl());
        assertEquals("true", settings.noProxy());
        assertEquals(Map.of("a", "b"), settings.awsSessionTags());
    }

    @Test
    void negativeHeartbeatIsNone() throws Exception {
        assertEquals(
                0,
                Settings.of(entry("{\"SSE_HEARTBEAT_INTERVAL\": -1}"), Map.of()).sseHeartbeatInterval());
    }

    @Test
    void nullIsTheSameAsAbsent() throws Exception {
        Map<String, String> environment = Map.of("HTTP_MAX_CONNECTIONS", "5", "DIAL_URL", "env");
        Settings settings = Settings.of(entry("{\"HTTP_MAX_CONNECTIONS\": null}"), environment);
        assertEquals(Settings.of(entry("{}"), environment), settings);
        assertEquals(5, settings.httpMaxConnections());
        assertNull(Settings.of(entry("{\"DIAL_URL\": null}"), Map.of()).dialUrl());
    }

    @Test
    void entryOverridesTheEnvironmentWithoutReadingIt() throws Exception {
        Settings settings =
                Settings.of(entry("{\"HTTP_MAX_CONNECTIONS\": 10}"), Map.of("HTTP_MAX_CONNECTIONS", "many"));
        assertEquals(10, settings.httpMaxConnections());
        ConfigException e = assertThrows(
                ConfigException.class, () -> Settings.of(entry("{}"), Map.of("HTTP_MAX_CONNECTIONS", "many")));
        assertEquals("HTTP_MAX_CONNECTIONS is invalid", e.getMessage());
    }

    @ParameterizedTest(name = "[{0}]")
    @CsvSource(
            delimiter = '|',
            value = {
                "{\"HTTP_MAX_CONNECTIONS\": \"many\"}|HTTP_MAX_CONNECTIONS is invalid",
                "{\"HTTP_MAX_CONNECTIONS\": \"1.5\"}|HTTP_MAX_CONNECTIONS is invalid",
                "{\"HTTP_MAX_CONNECTIONS\": \"\"}|HTTP_MAX_CONNECTIONS is invalid",
                "{\"AWS_SESSION_TAGS\": \"x\"}|AWS_SESSION_TAGS is invalid",
                "{\"HTTP_MAX_CONNECTIONS\": true}|HTTP_MAX_CONNECTIONS is invalid",
                "{\"HTTP_MAX_CONNECTIONS\": [1]}|HTTP_MAX_CONNECTIONS is invalid",
                "{\"DIAL_URL\": {}}|DIAL_URL is invalid",
                "{\"AWS_SESSION_TAGS\": {\"a\": null}}|AWS_SESSION_TAGS is invalid",
                "{\"AWS_SESSION_TAGS\": [\"a\"]}|AWS_SESSION_TAGS is invalid",
                "{\"adapter\": \"nope\"}|adapter is invalid",
                "{\"adapter\": 1}|adapter is invalid",
                "{\"SOMETHING_NEW\": \"x\"}|SOMETHING_NEW is invalid",
                "{\"PORT\": 80}|PORT is invalid"
            })
    void entryOfAnotherTypeIsRefused(String json, String message) throws Exception {
        ConfigException e = refused(json);
        assertEquals(message, e.getMessage());
        assertEquals(message.substring(0, message.indexOf(' ')), e.setting());
    }

    @Test
    void refusalNamesNoValue() throws Exception {
        ConfigException e = refused("{\"AWS_SECRET_ACCESS_KEY\": {\"k\": \"secret\"}}");
        assertFalse(e.getMessage().contains("secret"), e.getMessage());
        assertNull(e.getCause());
    }

    @Test
    void settingsDoNotPrint() throws Exception {
        assertFalse(Settings.of(entry("{\"AWS_SECRET_ACCESS_KEY\": \"secret\"}"), Map.of())
                .toString()
                .contains("secret"));
    }
}
