package com.epam.aidial.provider.json;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.epam.aidial.provider.api.InvalidRequestException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class BodyFieldsTest {

    @Test
    void parseKeepsSourceBytes() {
        BodyFields fs = BodyFields.parse(utf8(" { \"a\" : [ 1, 2 ] ,\"b\":\"x\\\"y\", \"c\":{ } } "), null);
        assertNotNull(fs);
        assertEquals("[ 1, 2 ]", text(fs.get("a")));
        assertEquals("\"x\\\"y\"", text(fs.get("b")));
        assertEquals("{ }", text(fs.get("c")));
        assertEquals(
                "{\"a\":[ 1, 2 ],\"c\":{ },\"d\":1}",
                text(fs.delete("b").set("d", "1").encode()));
    }

    static Stream<Arguments> refusedBodies() {
        return Stream.of(
                arguments("[]", "The request body must be a JSON object, not an array"),
                arguments("\"a\"", "The request body must be a JSON object, not a string"),
                arguments("1", "The request body must be a JSON object, not a number"),
                arguments("true", "The request body must be a JSON object, not a boolean"),
                arguments("null", "The request body must be a JSON object, not null"),
                arguments("", "The request body is empty"),
                arguments("{\"a\":1}{}", "The request body is not valid JSON: data follows the object"),
                arguments(
                        "{\"a\":1} x",
                        "The request body is not valid JSON: Unrecognized token 'x': was expecting (JSON String, Number, Array, "
                                + "Object or token 'null', 'true' or 'false')"),
                arguments(
                        "{\"a\":}",
                        "The request body is not valid JSON: Unexpected character ('}' (code 125)): expected a value"),
                arguments(
                        "{\"a\":1",
                        "The request body is not valid JSON: Unexpected end-of-input: expected close marker for Object"),
                // A repeated name at any depth, also inside a value that is only skipped.
                arguments("{\"a\":1,\"a\":2}", "The request body is not valid JSON: Duplicate field 'a'"),
                arguments(
                        "{\"model\":\"m\",\"x\":{\"a\":{\"b\":1,\"b\":2}}}",
                        "The request body is not valid JSON: Duplicate field 'b'"),
                arguments(
                        "{\"messages\":[{\"role\":\"a\",\"role\":\"b\"}]}",
                        "The request body is not valid JSON: Duplicate field 'role'"),
                arguments(
                        "{\"a\":NaN}",
                        "The request body is not valid JSON: Non-standard token 'NaN': enable "
                                + "`JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS` to allow"),
                arguments(
                        "{\"a\":01}",
                        "The request body is not valid JSON: Invalid numeric value: Leading zeroes not allowed"),
                arguments(
                        "{\"a\":\"x\ty\"}",
                        "The request body is not valid JSON: Illegal unquoted character ((CTRL-CHAR, code 9)): has to be "
                                + "escaped using backslash to be included in string value"));
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("refusedBodies")
    void parseRefusesWhatIsNotOneJsonObject(String body, String message) {
        assertEquals(message, refusal(utf8(body)));
    }

    @Test
    void parseRefusesBytesThatAreNotUtf8() {
        byte[] notUtf8 = {'{', '"', 'a', '"', ':', '"', 'm', (byte) 0xff, '"', '}'};
        assertEquals("The request body is not valid JSON: Invalid UTF-8 start byte 0xff", refusal(notUtf8));
        byte[] cutSequence = {'{', '"', 'a', '"', ':', '"', 'm', (byte) 0xc3, '"', '}'};
        assertEquals("The request body is not valid JSON: Invalid UTF-8 middle byte 0x22", refusal(cutSequence));
    }

    private static String refusal(byte[] body) {
        return assertThrows(InvalidRequestException.class, () -> BodyFields.parse(body, null))
                .getMessage();
    }

    @Test
    void parseTakesLargeValues() {
        String big = "a".repeat(30 << 20);
        BodyFields fs = BodyFields.parse(utf8("{\"model\":\"m\",\"messages\":[{\"content\":\"" + big + "\"}]}"), null);
        assertNotNull(fs);
        assertEquals("\"m\"", text(fs.get("model")));
        assertFalse(fs.isNull("messages"));
    }

    @Test
    void namesQuotedMinimally() {
        BodyFields fs = BodyFields.parse(utf8("{\"<a&b>\":1,\"q\\\"\\u00e9\":2}"), null);
        assertEquals("{\"<a&b>\":1,\"q\\\"é\":2}", text(fs.encode()));
        BodyFields controls = BodyFields.parse(utf8("{\"a\\u0001\\n\\\\\":1}"), null);
        assertEquals("{\"a\\u0001\\n\\\\\":1}", text(controls.encode()));
    }

    static Stream<Arguments> truthyValues() {
        return Stream.of(
                arguments("true", true),
                arguments("false", false),
                arguments("null", false),
                arguments("\"\"", false),
                arguments("\"a\"", true),
                arguments("[]", false),
                arguments("[ ]", false),
                arguments("[0]", true),
                arguments("{}", false),
                arguments("{\"a\":1}", true),
                arguments("0", false),
                arguments("0.0", false),
                arguments("2", true));
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("truthyValues")
    void truthy(String raw, boolean want) {
        assertEquals(want, BodyFields.truthy(utf8(raw)));
    }

    @Test
    void nullIsNotTruthy() {
        assertFalse(BodyFields.truthy(null));
    }

    @Test
    void untouchedBodyIsTheSameBytes() {
        byte[] body = utf8(
                """
                        { "model" : "claude-x", "messages":[ {"role":"user","content":[{"type":"text","text":"hi é"}]} ] }""");
        BodyFields fs = BodyFields.parse(body, null);
        assertNotNull(fs);
        byte[] rewritten = fs.delete("model")
                .set("anthropic_version", "\"vertex-2023-10-16\"")
                .encode();
        assertArrayEquals(
                utf8(
                        """
                                {"messages":[ {"role":"user","content":[{"type":"text","text":"hi é"}]} ],"anthropic_version":"vertex-2023-10-16"}"""),
                rewritten);
        assertTrue(BodyFields.parse(rewritten, null) != null);
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }
}
