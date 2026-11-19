package com.epam.aidial.provider.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResponsesUrlsTest {

    private static Map<String, String> params(String... pairs) {
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            params.put(pairs[i], pairs[i + 1]);
        }
        return params;
    }

    @Test
    void queryLeavesOutEmptyValuesAndTheClientsApiVersion() {
        assertEquals(
                "api-version=v&b=2&a=x+y",
                Target.query("v", params("b", "2", "a", "x y", "e", "", "api-version", "client")));
        assertEquals("b=2", Target.query(null, params("b", "2")));
        assertEquals("b=2", Target.query("", params("b", "2")));
    }

    @Test
    void queryKeepsTheCharactersValuesMayHoldAndEscapesTheRest() {
        Map.of(
                        "☃", "%E2%98%83",
                        "a+b", "a%2Bb",
                        "a b", "a+b",
                        "=&", "%3D%26",
                        "!$'()*,;:@/?", "!$'()*,%3B:@/?",
                        "-._~", "-._~")
                .forEach((value, want) -> assertEquals("x=" + want, Target.query(null, params("x", value)), value));
    }

    @Test
    void pathIsQuotedOnceAndItsDotSegmentsAreResolved() {
        String base = "http://h:1/v1/responses";
        assertEquals(
                "http://h:1/v1/responses/resp%201:%C3%A9!$&'()*+,;=@", Target.withPath(base, "/resp 1:é!$&'()*+,;=@"));
        assertEquals("http://h:1/v1/responses/a%22%23%3C%3E%3F%60%7B%7D", Target.withPath(base, "/a\"#<>?`{}"));
        assertEquals("http://h:1/v1", Target.withPath(base, "/.."));
        assertEquals("http://h:1/v1/responses", Target.withPath(base, "/."));
        assertEquals("http://h:1/v1/cancel", Target.withPath(base, "/../cancel"));
        assertEquals("http://h:1/v1/responses/a.b", Target.withPath(base, "/a.b"));
        assertEquals("http://h:1/v1/responses", Target.withPath(base, ""));
        assertEquals("http://h:1/v1/x/", Target.withPath("http://h:1/v1/./x/", ""));
    }

    @Test
    void pathNeverClimbsAboveTheRoot() {
        assertEquals("http://h", Target.withPath("http://h/responses", "/../../.."));
        assertEquals("http://h/x", Target.withPath("http://h/responses", "/../../../x"));
        assertEquals("http://h", Target.withPath("http://h", ""));
    }
}
