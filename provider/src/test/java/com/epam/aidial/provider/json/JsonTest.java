package com.epam.aidial.provider.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.epam.aidial.provider.api.InvalidRequestException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void parse() throws Exception {
        Object v = Json.parse(
                """
                {"a":1,"b":[true,null,1.5,"x"],"a":12345678901,"c":123456789012345678901}""");
        assertEquals(
                Map.of(
                        "a",
                        12345678901L,
                        "b",
                        Arrays.asList(true, null, 1.5, "x"),
                        "c",
                        new BigInteger("123456789012345678901")),
                v);
        assertEquals(List.of("a", "b", "c"), List.copyOf(((Map<?, ?>) v).keySet()));
        assertThrows(Json.ParseException.class, () -> Json.parse("{} x"));
        assertThrows(Json.ParseException.class, () -> Json.parse(""));
        assertThrows(Json.ParseException.class, () -> Json.parse("NaN"));
        assertThrows(Json.ParseException.class, () -> Json.parse(new byte[] {'"', (byte) 0xff, '"'}));
        assertThrows(Json.ParseException.class, () -> Json.parse("[".repeat(1001) + "]".repeat(1001)));
        Object deepest = Json.parse("[".repeat(1000) + "]".repeat(1000));
        assertEquals("{\"a\":" + "[".repeat(1000) + "]".repeat(1000) + "}", Json.encode(Map.of("a", deepest)));
        assertThrows(Json.ParseException.class, () -> Json.parse("1".repeat(1001)));
        String message = assertThrows(Json.ParseException.class, () -> Json.parse("{\"a\":tru}"))
                .getMessage();
        assertFalse(message.contains("line") || message.contains("Source") || message.contains("\"a\""), message);
        for (String unclosed : List.of("[1,2", "{\"a\":[1")) {
            message = assertThrows(Json.ParseException.class, () -> Json.parse(unclosed))
                    .getMessage();
            assertFalse(message.isBlank() || message.contains("Source") || message.contains("marker at"), message);
        }
        String big = "x".repeat(21_000_000); // past Jackson's default string limit
        assertEquals(big, Json.parse("\"" + big + "\""));
    }

    @Test
    void parseOrNull() {
        assertNull(Json.parseOrNull("{".getBytes(StandardCharsets.UTF_8)));
        assertNull(Json.parseOrNull(null));
    }

    private static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void getStrAndGetBool() {
        assertNull(Json.getStr(null, "model"));
        assertNull(Json.getStr(bytes("null"), "model"));
        assertEquals("gpt", Json.getStr(bytes("\"gpt\""), "model"));
        assertEquals("", Json.getStr(bytes("\"\""), "model"));
        assertNull(Json.getBool(null, "stream"));
        assertNull(Json.getBool(bytes("null"), "stream"));
        assertEquals(true, Json.getBool(bytes("true"), "stream"));
        assertEquals(false, Json.getBool(bytes("false"), "stream"));
        Map<String, String> wrongForStr = Map.of(
                "true", "a boolean",
                "false", "a boolean",
                "1", "a number",
                "123456789012345678901", "a number",
                "1.0", "a number",
                "1e2", "a number",
                "[]", "an array",
                "{}", "an object");
        wrongForStr.forEach((raw, type) -> assertEquals(
                "\"model\" must be a string, but is " + type,
                assertThrows(InvalidRequestException.class, () -> Json.getStr(bytes(raw), "model"))
                        .getMessage(),
                raw));
        Map<String, String> wrongForBool = Map.of(
                "\"x\"", "a string",
                "\"true\"", "a string",
                "0", "a number",
                "1", "a number",
                "1.5", "a number",
                "[]", "an array",
                "{}", "an object");
        wrongForBool.forEach((raw, type) -> assertEquals(
                "\"stream\" must be a boolean, but is " + type,
                assertThrows(InvalidRequestException.class, () -> Json.getBool(bytes(raw), "stream"))
                        .getMessage(),
                raw));
        // raw is a member as received: valid JSON, else the parse's own failure
        assertThrows(Json.ParseException.class, () -> Json.getStr(bytes("\"x"), "model"));
        assertThrows(Json.ParseException.class, () -> Json.getBool(bytes("tru"), "stream"));
    }

    @Test
    void parseExceptionIsUnchecked() {
        RuntimeException e = new Json.ParseException("x");
        assertEquals("x", e.getMessage());
    }

    @Test
    void encodeAndText() {
        assertEquals("{\"a\":[1,2.5,\"é\",null]}", Json.encode(Map.of("a", Arrays.asList(1, 2.5, "é", null))));
        assertEquals("x", Json.text("x"));
        assertEquals("[null,null,null,1.0,-0.5]", Json.encode(List.of(Double.NaN, 1 / 0.0, -1 / 0.0, 1.0, -0.5)));
        assertEquals("1", Json.text(1));
        assertEquals("null", Json.text(null));
    }

    @Test
    void stringMap() {
        assertEquals(
                List.of("z", "a"),
                List.copyOf(Json.parseStringMap("{\"z\":\"1\",\"a\":\"\"}").keySet()));
        assertEquals(Map.of(), Json.parseStringMap("{}"));
        for (String bad : List.of(
                "", "x", "null", "[]", "\"s\"", "{\"a\":1}", "{\"a\":true}", "{\"a\":null}", "{\"a\":[]}", "{} x")) {
            assertThrows(Json.ParseException.class, () -> Json.parseStringMap(bad), bad);
        }
    }
}
