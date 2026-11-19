package com.epam.aidial.provider.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StrippingTest {

    private static final Set<String> AWS = Set.of("advisor", "web_search");
    private static final Set<String> AZURE = Set.of("advisor");

    @Test
    void dropBetas() {
        assertEquals("a,b", Stripping.dropBetas(" a, x,,b ", Set.of("x")));
        assertEquals("", Stripping.dropBetas("x", Set.of("x")));
    }

    @Test
    void dropTools() {
        assertEquals(
                """
                        [{"name":"get","input_schema":{"type":"object"}}]""",
                text(Stripping.dropTools(
                        utf8(
                                """
                                        [{"type":"advisor_20260301","name":"advisor"},{"name":"get","input_schema":{"type":"object"}}]"""),
                        AZURE)));
        assertEquals(
                "[{ \"name\" : \"t\", \"description\" : \"<a>\" }]",
                text(Stripping.dropTools(
                        utf8(
                                """
                                        [{"name":"web_search","type":"web_search_20250305"},{ "name" : "t", "description" : "<a>" }]"""),
                        AWS)));
        assertEquals(
                """
                        [{"type":"custom","name":"advisor"},{"name":"advisor"},{"name":"t"}]""",
                text(Stripping.dropTools(
                        utf8(
                                """
                                        [{"type":"advisor_20260301","name":"advisor"},{"type":"custom","name":"advisor"},{"name":"advisor"},{"name":"t"}]"""),
                        AZURE)));
        assertNull(Stripping.dropTools(utf8("[{\"name\":\"t\"}]"), AWS), "nothing dropped");
        assertNull(Stripping.dropTools(utf8("{\"name\":\"advisor\"}"), AWS), "not an array");
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }
}
