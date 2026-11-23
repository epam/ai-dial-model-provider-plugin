package com.epam.aidial.provider.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.epam.aidial.provider.api.CacheBreakpoint;
import com.epam.aidial.provider.json.BodyFields;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class CacheBreakpointsTest {

    record Case(String body, String path, int ttl) {}

    static Stream<Arguments> breakpointCases() {
        String cc = "\"cache_control\":{\"type\":\"ephemeral\"}";
        Map<String, Case> cases = new LinkedHashMap<>();
        cases.put("none", new Case("{\"messages\":[{\"content\":\"hi\"}]}", null, 0));
        cases.put("no messages", new Case("{\"system\":[{\"text\":\"s\"," + cc + "}]}", null, 0));
        cases.put(
                "last marker wins",
                new Case(
                        "{\"tools\":[{" + cc + "}],\"system\":[{\"text\":\"s\"," + cc
                                + "}],\"messages\":[{\"content\":[{\"text\":\"a\"}]}]}",
                        "prefix.body.system[0]",
                        300));
        cases.put(
                "longest ttl",
                new Case(
                        "{\"tools\":[{\"cache_control\":{\"type\":\"ephemeral\",\"ttl\":\"1h\"}}],\"messages\":[{\"content\":[{"
                                + cc + "}]}]}",
                        "prefix.body.messages[0].content[0]",
                        3600));
        cases.put(
                "not ephemeral",
                new Case(
                        """
                        {"messages":[{"content":[{"cache_control":{"type":"other"}}]}]}""",
                        null,
                        0));
        cases.put(
                "automatic, scalar last",
                new Case(
                        """
                                {"cache_control":{"type":"ephemeral","ttl":"30s"},"system":"s","messages":[{"content":[{"text":"a"}]},{"content":"b"}]}""",
                        "prefix.body.messages[1].content[0]",
                        30));
        cases.put(
                "automatic, no blocks",
                new Case("""
                        {"cache_control":{"type":"ephemeral"},"messages":[]}""", null, 0));
        cases.put(
                "automatic, not object",
                new Case("{\"cache_control\":[1],\"messages\":[{\"content\":[{" + cc + "}]}]}", null, 0));
        cases.put(
                "bad ttl is default",
                new Case(
                        """
                                {"messages":[{"content":[{"cache_control":{"type":"ephemeral","ttl":"xm"}}]}]}""",
                        "prefix.body.messages[0].content[0]",
                        300));
        cases.put(
                "non-object entries",
                new Case(
                        "{\"system\":[\"s\",{" + cc + "}],\"messages\":[\"m\",{\"content\":[1,{" + cc + "}]}]}",
                        "prefix.body.messages[1].content[1]",
                        300));
        cases.put(
                "prompt order, not body order (automatic)",
                new Case(
                        """
                                {"cache_control":{"type":"ephemeral"},"messages":[{"content":"m"}],"system":"s"}""",
                        "prefix.body.messages[0].content[0]",
                        300));
        cases.put(
                "prompt order, not body order (system before tools)",
                new Case(
                        "{\"system\":[{\"text\":\"s\"," + cc
                                + "}],\"messages\":[{\"content\":[{\"text\":\"a\"}]}],\"tools\":[{" + cc + "}]}",
                        "prefix.body.system[0]",
                        300));
        cases.put(
                "prompt order, not body order (messages before system)",
                new Case(
                        "{\"messages\":[{\"content\":[{\"text\":\"a\"," + cc + "}]}],\"system\":[{\"text\":\"s\"," + cc
                                + "}]}",
                        "prefix.body.messages[0].content[0]",
                        300));
        cases.put(
                "prompt order, not body order (messages before tools)",
                new Case(
                        "{\"messages\":[{\"content\":[{\"text\":\"a\"," + cc + "}]}],\"tools\":[{" + cc + "}]}",
                        "prefix.body.messages[0].content[0]",
                        300));
        cases.put(
                "automatic, last block of the prompt whatever the body order",
                new Case(
                        """
                                {"messages":[{"content":[{"text":"a"}]}],"cache_control":{"type":"ephemeral"},"tools":[{"name":"t"}],"system":[{"text":"s"}]}""",
                        "prefix.body.messages[0].content[0]",
                        300));
        cases.put(
                "object system is not a marker",
                new Case("{\"system\":{\"text\":\"s\"," + cc + "},\"messages\":[]}", null, 0));
        cases.put(
                "messages not an array",
                new Case(
                        """
                        {"cache_control":{"type":"ephemeral"},"messages":{"content":"m"}}""",
                        null,
                        0));
        return cases.entrySet().stream().map(e -> arguments(Named.of(e.getKey(), e.getValue())));
    }

    @ParameterizedTest
    @MethodSource("breakpointCases")
    void cacheBreakpoint(Case k) {
        CacheBreakpoints cache = new CacheBreakpoints();
        BodyFields fs = BodyFields.parse(utf8(k.body), cache);
        assertNotNull(fs);
        CacheBreakpoint got = cache.breakpoint(fs);
        assertEquals(k.path, got == null ? null : got.path().value());
        assertEquals(k.ttl, got == null ? 0 : got.ttlSeconds());
    }

    @Test
    void ttlSeconds() {
        assertEquals(3600, CacheBreakpoints.ttlSeconds("1h"));
        assertEquals(60, CacheBreakpoints.ttlSeconds(" 1 m".strip()));
        assertEquals(300, CacheBreakpoints.ttlSeconds("xm"));
        assertEquals(300, CacheBreakpoints.ttlSeconds(null));
        assertEquals(300, CacheBreakpoints.ttlSeconds("5d"));
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {"-5m", "0s", "0h", "-1s"})
    void nonPositiveTtlIsTheDefault(String ttl) {
        assertEquals(300, CacheBreakpoints.ttlSeconds(ttl));
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
