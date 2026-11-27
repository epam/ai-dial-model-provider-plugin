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
        return (ObjectNode) MAPPER.readTree(json);
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
    void entryIsJsonOfTheTypes() throws Exception {
        Settings settings = Settings.of(
                entry("{\"HTTP_MAX_CONNECTIONS\": 64, \"DIAL_URL\": \"u\", \"AWS_SESSION_TAGS\": {\"a\": \"b\"}}"),
                Map.of());
        assertEquals(64, settings.httpMaxConnections());
        assertEquals("u", settings.dialUrl());
        assertEquals(Map.of("a", "b"), settings.awsSessionTags());
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
        assertEquals("HTTP_MAX_CONNECTIONS must be an integer", e.getMessage());
    }

    @ParameterizedTest(name = "[{0}]")
    @CsvSource(
            delimiter = '|',
            value = {
                "{\"HTTP_MAX_CONNECTIONS\": \"10\"}|HTTP_MAX_CONNECTIONS must be an integer",
                "{\"HTTP_MAX_CONNECTIONS\": 1.5}|HTTP_MAX_CONNECTIONS must be an integer",
                "{\"HTTP_MAX_CONNECTIONS\": true}|HTTP_MAX_CONNECTIONS must be an integer",
                "{\"HTTP_MAX_CONNECTIONS\": [1]}|HTTP_MAX_CONNECTIONS must be an integer",
                "{\"SSE_HEARTBEAT_INTERVAL\": \"1\"}|SSE_HEARTBEAT_INTERVAL must be a non-negative integer",
                "{\"SSE_HEARTBEAT_INTERVAL\": -1}|SSE_HEARTBEAT_INTERVAL must be a non-negative integer",
                "{\"DIAL_URL\": 1}|DIAL_URL must be a string",
                "{\"DIAL_URL\": true}|DIAL_URL must be a string",
                "{\"DIAL_URL\": {}}|DIAL_URL must be a string",
                "{\"AWS_SESSION_TAGS\": \"{}\"}|AWS_SESSION_TAGS must be a JSON object of strings",
                "{\"AWS_SESSION_TAGS\": {\"a\": 1}}|AWS_SESSION_TAGS must be a JSON object of strings",
                "{\"AWS_SESSION_TAGS\": {\"a\": null}}|AWS_SESSION_TAGS must be a JSON object of strings",
                "{\"AWS_SESSION_TAGS\": [\"a\"]}|AWS_SESSION_TAGS must be a JSON object of strings",
                "{\"SOMETHING_NEW\": \"x\"}|SOMETHING_NEW is not a setting",
                "{\"PORT\": 80}|PORT is not a setting"
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
