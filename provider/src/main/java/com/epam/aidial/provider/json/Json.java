package com.epam.aidial.provider.json;

import com.epam.aidial.provider.api.InvalidRequestException;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamWriteConstraints;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.fasterxml.jackson.databind.type.LogicalType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * JSON off the hot path, through Jackson. A decoded value is {@code null}, {@link Boolean}, {@link String}, {@link
 * Number} ({@link Integer}, {@link Long} or {@link BigInteger} for an integer, {@link Double} otherwise), {@code
 * List<Object>} or {@code Map<String, Object>} (in document order; a repeated name keeps its last value). Jackson's
 * default read limits hold but for the string length: nesting deeper than 1,000 or a number of more than 1,000 digits
 * is not JSON here.
 */
public final class Json {

    private static final ObjectMapper MAPPER = JsonMapper.builder(JsonFactory.builder()
                    // a request or an answer may hold megabytes in one string: an attachment, an image, a video
                    .streamReadConstraints(StreamReadConstraints.builder()
                            .maxStringLength(Integer.MAX_VALUE)
                            .build())
                    // a value read at the deepest nesting may be written inside a body built around it
                    .streamWriteConstraints(StreamWriteConstraints.builder()
                            .maxNestingDepth(Integer.MAX_VALUE)
                            .build())
                    .build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .addModule(new SimpleModule().addSerializer(Double.class, new FiniteOrNull()))
            .build();

    /** Strict about strings (Jackson would coerce a number or a boolean to one): a header must say what it means. */
    private static final JsonMapper BINDER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .withConfigOverride(
                    Map.class, override -> override.setSetterInfo(JsonSetter.Value.forContentNulls(Nulls.FAIL)))
            .withCoercionConfig(
                    LogicalType.Textual, config -> config.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                            .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                            .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
            .build();

    private static final ObjectReader STRING_MAP = BINDER.rebuild()
            .defaultSetterInfo(JsonSetter.Value.forContentNulls(Nulls.FAIL))
            .build()
            .readerFor(new TypeReference<Map<String, String>>() {});

    private static final ObjectReader TREE = MAPPER.readerFor(Object.class);

    /** The tail Jackson adds to the message of an unclosed array or object: it names the source. */
    private static final Pattern START_MARKER = Pattern.compile(" ?\\(start marker at \\[Source:[^\\]]*\\]\\)");

    private static final byte[] TRUE = "true".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] FALSE = "false".getBytes(StandardCharsets.US_ASCII);

    private Json() {}

    /**
     * Reading throws {@link com.fasterxml.jackson.core.JsonParseException} when the document is not JSON and another
     * {@link JsonProcessingException} when it does not fit the type; a JSON null reads as null.
     */
    public static ObjectReader binder(Class<?> type) {
        return BINDER.readerFor(type);
    }

    /**
     * @throws ParseException when raw is not JSON, not an object, or has a member that is no string (null included)
     */
    public static Map<String, String> parseStringMap(String raw) {
        Map<String, String> map;
        try {
            map = STRING_MAP.readValue(raw);
        } catch (JsonProcessingException e) {
            throw new ParseException(e.getOriginalMessage());
        }
        if (map == null) {
            throw new ParseException("not a JSON object");
        }
        return map;
    }

    public static final class ParseException extends RuntimeException {

        public ParseException(String message) {
            super(message, null, false, false);
        }
    }

    /**
     * @throws ParseException when body is not one JSON document
     */
    public static Object parse(byte[] body) {
        return read(TREE, body);
    }

    public static Object parse(String doc) {
        return read(TREE, doc.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Null when raw is absent or JSON null.
     *
     * @throws InvalidRequestException when the value is of another type: {@code "name" must be a string, but is a
     *     number}; a boolean is no string
     * @throws ParseException when raw is not one JSON document
     */
    public static @Nullable String getStr(byte @Nullable [] raw, String name) {
        return scalar(raw, name, String.class, "a string");
    }

    /**
     * As {@link #getStr}; a number is no boolean. The literals {@code true} and {@code false} are told apart without a
     * parse: a stream flag is read on every request.
     *
     * @throws InvalidRequestException when the value is of another type
     * @throws ParseException when raw is not one JSON document
     */
    public static @Nullable Boolean getBool(byte @Nullable [] raw, String name) {
        if (Arrays.equals(raw, TRUE)) {
            return Boolean.TRUE;
        }
        if (Arrays.equals(raw, FALSE)) {
            return Boolean.FALSE;
        }
        return scalar(raw, name, Boolean.class, "a boolean");
    }

    private static <T> @Nullable T scalar(byte @Nullable [] raw, String name, Class<T> type, String expected) {
        Object value = raw == null ? null : parse(raw);
        if (value == null) {
            return null;
        }
        if (!type.isInstance(value)) {
            throw new InvalidRequestException(
                    Json.encode(name) + " must be " + expected + ", but is " + describe(value));
        }
        return type.cast(value);
    }

    private static String describe(Object value) {
        return switch (value) {
            case String ignored -> "a string";
            case Boolean ignored -> "a boolean";
            case Number ignored -> "a number";
            case List<?> ignored -> "an array";
            default -> "an object";
        };
    }

    /** Null for no bytes, a document that is not JSON and a JSON null alike: for a caller that looks for one type. */
    public static @Nullable Object parseOrNull(byte @Nullable [] raw) {
        if (raw == null) {
            return null;
        }
        try {
            return parse(raw);
        } catch (ParseException e) {
            return null;
        }
    }

    private static Object read(ObjectReader reader, byte[] body) {
        try {
            return reader.readValue(body);
        } catch (JsonProcessingException e) {
            throw new ParseException(message(e));
        } catch (IOException e) {
            throw new ParseException(e.getMessage());
        }
    }

    static String message(JsonProcessingException e) {
        return START_MARKER.matcher(e.getOriginalMessage()).replaceAll("");
    }

    public static String encode(Object v) {
        try {
            return MAPPER.writeValueAsString(v);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String text(Object v) {
        return v instanceof String s ? s : encode(v);
    }

    /** JSON has no NaN or infinity: null, not Jackson's default "NaN" strings. */
    private static final class FiniteOrNull extends StdSerializer<Double> {

        FiniteOrNull() {
            super(Double.class);
        }

        @Override
        public void serialize(Double v, JsonGenerator g, SerializerProvider provider) throws IOException {
            if (Double.isFinite(v)) {
                g.writeNumber(v);
            } else {
                g.writeNull();
            }
        }
    }
}
