package com.epam.aidial.provider.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.epam.aidial.provider.api.ConfigException;
import com.epam.aidial.provider.upstream.Limits;
import com.epam.aidial.provider.upstream.Limits.PoolWait;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

class ConfigTest {

    private static PoolWait bounded(long millis) {
        return new PoolWait.Bounded(Duration.ofMillis(millis));
    }

    private static Limits limits(int max, PoolWait wait, long readTimeoutMillis) {
        return new Limits(OptionalInt.of(max), wait, Duration.ofMillis(readTimeoutMillis));
    }

    @Test
    void defaults() {
        Config config = Config.from(Map.of());
        assertEquals(5000, config.port());
        assertEquals(limits(1000, bounded(10_000), 600_000), config.openai());
        assertEquals(limits(250, bounded(10_000), 600_000), config.vertexai());
        assertEquals(limits(1000, bounded(10_000), 600_000), config.bedrock());
        assertNull(config.sessionTags());
        assertNull(config.dialUrl());
        assertEquals("", config.awsBedrockBaseUrl());
        assertEquals("https://api.anthropic.com", config.anthropicBaseUrl());
    }

    /** operations "Upstream settings not checked at start": the endpoints, the default client and the retry settings. */
    @Test
    void upstreamSettingsAreTakenAsTheyAre() {
        Config.from(Map.of(
                "AWS_ENDPOINT_URL_BEDROCK_RUNTIME",
                "::",
                "AWS_CLAUDE_DEFAULT_CLIENT",
                "bogus",
                "ANTHROPIC_MAX_RETRY_ATTEMPTS",
                "y",
                "COMPATIBILITY_MAPPING",
                "x",
                "CLAUDE_DEFAULT_MAX_TOKENS",
                "x"));
    }

    @Test
    void heartbeatIsWholeSecondsAndZeroIsNone() {
        assertEquals(0, Config.from(Map.of()).heartbeatSeconds());
        assertEquals(0, Config.from(Map.of("SSE_HEARTBEAT_INTERVAL", "0")).heartbeatSeconds());
        assertEquals(15, Config.from(Map.of("SSE_HEARTBEAT_INTERVAL", "15")).heartbeatSeconds());
    }

    @Test
    void negativeHeartbeatIsNone() {
        assertEquals(0, Config.from(Map.of("SSE_HEARTBEAT_INTERVAL", "-1")).heartbeatSeconds());
    }

    @Test
    void portIsRead() {
        assertEquals(8080, Config.from(Map.of("PORT", " 8080 ")).port());
        assertEquals(0, Config.from(Map.of("PORT", "0")).port());
    }

    @Test
    void oneVariableTwoAdapters() {
        Config config = Config.from(Map.of("HTTP_MAX_CONNECTIONS", "64", "HTTP_POOL_TIMEOUT", "0"));
        assertEquals(limits(64, new PoolWait.Unbounded(), 600_000), config.openai());
        assertEquals(limits(64, new PoolWait.Unbounded(), 600_000), config.vertexai());
        assertEquals(OptionalInt.of(1000), config.bedrock().maxConnections());
    }

    @Test
    void bedrockConnections() {
        Config config = Config.from(Map.of(
                "ANTHROPIC_MAX_CONNECTIONS", "5", "ANTHROPIC_POOL_TIMEOUT", "0", "REQUEST_TIMEOUT_SECONDS", "-3"));
        assertEquals(limits(5, new PoolWait.FailFast(), 0), config.bedrock());
        assertEquals(
                limits(5, bounded(2000), 2000),
                Config.from(Map.of(
                                "ANTHROPIC_MAX_CONNECTIONS",
                                "5",
                                "ANTHROPIC_POOL_TIMEOUT",
                                "2",
                                "REQUEST_TIMEOUT_SECONDS",
                                "2"))
                        .bedrock());
        Limits uncapped = Config.from(Map.of("HTTP_POOL_TIMEOUT", "-5", "HTTP_MAX_CONNECTIONS", "-5"))
                .openai();
        assertEquals(OptionalInt.empty(), uncapped.maxConnections());
        assertEquals(new PoolWait.Unbounded(), uncapped.poolWait());
    }

    static Stream<Arguments> invalidSettings() {
        List<Arguments> cases = new ArrayList<>();
        for (String port : new String[] {"x", "", "-1", "65536", "5000.0"}) {
            cases.add(arguments(Map.of("PORT", port), "PORT must be an integer from 0 to 65535"));
        }
        for (String name : List.of(
                "HTTP_MAX_CONNECTIONS",
                "HTTP_POOL_TIMEOUT",
                "ANTHROPIC_MAX_CONNECTIONS",
                "ANTHROPIC_POOL_TIMEOUT",
                "REQUEST_TIMEOUT_SECONDS")) {
            for (String bad : new String[] {"abc", "1.5", ""}) {
                cases.add(arguments(Map.of(name, bad), name + " is invalid"));
            }
        }
        for (String bad : new String[] {"1s", "", "x", "0.5", "1e3", "Infinity", "NaN"}) {
            cases.add(arguments(Map.of("SSE_HEARTBEAT_INTERVAL", bad), "SSE_HEARTBEAT_INTERVAL is invalid"));
        }
        for (String bad : new String[] {"", "x", "[]", "{\"a\":\"b\",\"c\":null}"}) {
            cases.add(arguments(Map.of("AWS_SESSION_TAGS", bad), "AWS_SESSION_TAGS is invalid"));
        }
        // Only the first failure, in operations' order.
        cases.add(arguments(
                Map.of("ANTHROPIC_POOL_TIMEOUT", "x", "REQUEST_TIMEOUT_SECONDS", "y", "SSE_HEARTBEAT_INTERVAL", "z"),
                "ANTHROPIC_POOL_TIMEOUT is invalid"));
        cases.add(arguments(
                Map.of("HTTP_MAX_CONNECTIONS", "abc", "AWS_SESSION_TAGS", "x"), "HTTP_MAX_CONNECTIONS is invalid"));
        return cases.stream();
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("invalidSettings")
    void invalidSettingsStopTheStart(Map<String, String> settings, String message) {
        ConfigException e = assertThrows(ConfigException.class, () -> Config.from(settings));
        assertEquals(message, e.getMessage());
        assertTrue(message.startsWith(e.setting() + " "));
    }

    @ParameterizedTest(name = "[{0}]")
    @CsvSource(
            quoteCharacter = '\'',
            value = {
                "'', ''",
                "' ', ''",
                "' https://h.test/openai/v1/ ', https://h.test/openai/v1",
                "https://h.test/openai/v1/responses, https://h.test/openai/v1",
                "https://h.test/openai/v1/responses/x?q=1#f, https://h.test/openai/v1?q=1#f",
                "https://h.test, https://h.test",
                "host.test/, host.test"
            })
    void bedrockBaseUrlIsTrimmedAndCutAtTheResponsesSegment(String raw, String want) {
        assertEquals(want, Config.from(Map.of("AWS_BEDROCK_BASE_URL", raw)).awsBedrockBaseUrl());
    }

    @Test
    void sessionTagsKeepDictOrder() {
        assertNull(Config.from(Map.of("AWS_SESSION_TAGS", "{}")).sessionTags());
        Map<String, String> tags = Config.from(
                        Map.of(
                                "AWS_SESSION_TAGS",
                                """
                                {"a":"Bedrock.modelId","b":"Bedrock.modelId","a":"UserInfo.project"}"""))
                .sessionTags();
        assertEquals(List.of("a", "b"), List.copyOf(tags.keySet()));
        assertEquals("UserInfo.project", tags.get("a"));
    }
}
