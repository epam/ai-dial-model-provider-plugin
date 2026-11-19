package com.epam.aidial.provider.json;

import com.epam.aidial.provider.api.InvalidRequestException;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.io.JsonStringEncoder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Members stay source bytes so that the prompt is forwarded undecoded; only a changed member is encoded. */
public final class BodyFields {

    static final JsonFactory JSON = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            // A prompt may hold megabytes in one string: only the nesting depth is limited.
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxStringLength(Integer.MAX_VALUE)
                    .maxNameLength(Integer.MAX_VALUE)
                    .maxNestingDepth(10_000)
                    .maxNumberLength(Integer.MAX_VALUE)
                    .build())
            .build();

    private record Field(String name, byte[] body, int start, int end) {

        byte[] raw() {
            return start == 0 && end == body.length ? body : Arrays.copyOfRange(body, start, end);
        }
    }

    private static final byte[] NULL = {'n', 'u', 'l', 'l'};

    private final List<Field> fields;

    private BodyFields(List<Field> fields) {
        this.fields = fields;
    }

    /** Looks into a member's value while {@link #parse} passes over it, so that nothing is read twice. */
    public interface MemberScanner {

        /**
         * @param parser at the first token of the member's value
         * @return true when it read the whole value and left the parser on its last token; false to have parse skip the
         *     value
         */
        boolean scan(String name, JsonParser parser) throws IOException;
    }

    /**
     * @throws InvalidRequestException when body is not one JSON object: no body, invalid JSON (not UTF-8, {@code NaN}
     *     or a repeated member name at any depth included), another JSON value, or data after the object
     */
    public static BodyFields parse(byte[] body, @Nullable MemberScanner scanner) {
        try (JsonParser parser = JSON.createParser(body)) {
            JsonToken first = parser.nextToken();
            if (first != JsonToken.START_OBJECT) {
                throw new InvalidRequestException(
                        first == null
                                ? "The request body is empty"
                                : "The request body must be a JSON object, not " + describe(first));
            }
            List<Field> fields = new ArrayList<>();
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String name = parser.currentName();
                parser.nextToken();
                int start = (int) parser.currentTokenLocation().getByteOffset();
                if (scanner == null || !scanner.scan(name, parser)) {
                    parser.skipChildren();
                }
                parser.finishToken(); // a string is read lazily: its end is known only once finished
                fields.add(new Field(
                        name, body, start, (int) parser.currentLocation().getByteOffset()));
            }
            if (parser.currentToken() != JsonToken.END_OBJECT) {
                throw new InvalidRequestException("The request body is not valid JSON: the object is not closed");
            }
            if (parser.nextToken() != null) {
                throw new InvalidRequestException("The request body is not valid JSON: data follows the object");
            }
            return new BodyFields(fields);
        } catch (JsonProcessingException e) {
            throw new InvalidRequestException("The request body is not valid JSON: " + Json.message(e));
        } catch (IOException e) {
            throw new InvalidRequestException("The request body is not valid JSON: " + e.getMessage());
        }
    }

    private static String describe(JsonToken token) {
        return switch (token) {
            case START_ARRAY -> "an array";
            case VALUE_STRING -> "a string";
            case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> "a number";
            case VALUE_TRUE, VALUE_FALSE -> "a boolean";
            case VALUE_NULL -> "null";
            default -> "a " + token;
        };
    }

    /** -1 when the member is absent or no array. */
    public int arrayLength(String name) {
        Field f = field(name);
        if (f == null || f.body()[f.start()] != '[') {
            return -1;
        }
        try (JsonParser p = JSON.createParser(f.body(), f.start(), f.end() - f.start())) {
            p.nextToken();
            int n = 0;
            while (p.nextToken() != JsonToken.END_ARRAY) {
                p.skipChildren();
                n++;
            }
            return n;
        } catch (IOException e) {
            return -1; // a member of a body already scanned
        }
    }

    /** A copy: for the small members. */
    public byte @Nullable [] get(String name) {
        Field f = field(name);
        return f == null ? null : f.raw();
    }

    /** True for a JSON null too. */
    public boolean has(String name) {
        return field(name) != null;
    }

    /** Absent counts as null; compares in place, unlike {@link #get}. */
    public boolean isNull(String name) {
        Field f = field(name);
        return f == null || Arrays.equals(f.body, f.start, f.end, NULL, 0, NULL.length);
    }

    private @Nullable Field field(String name) {
        for (Field f : fields) {
            if (f.name.equals(name)) {
                return f;
            }
        }
        return null;
    }

    public BodyFields set(String name, byte[] raw) {
        Field field = new Field(name, raw, 0, raw.length);
        for (int i = 0; i < fields.size(); i++) {
            if (fields.get(i).name.equals(name)) {
                fields.set(i, field);
                return this;
            }
        }
        fields.add(field);
        return this;
    }

    public BodyFields set(String name, String json) {
        return set(name, json.getBytes(StandardCharsets.UTF_8));
    }

    public BodyFields delete(String name) {
        fields.removeIf(f -> f.name.equals(name));
        return this;
    }

    public byte[] encode() {
        JsonStringEncoder quoting = JsonStringEncoder.getInstance();
        int size = 2;
        List<byte[]> names = new ArrayList<>(fields.size());
        for (Field f : fields) {
            byte[] name = quoting.quoteAsUTF8(f.name);
            names.add(name);
            size += name.length + 4 + (f.end - f.start);
        }
        byte[] out = new byte[size];
        int at = 0;
        out[at++] = '{';
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                out[at++] = ',';
            }
            byte[] name = names.get(i);
            out[at++] = '"';
            System.arraycopy(name, 0, out, at, name.length);
            at += name.length;
            out[at++] = '"';
            out[at++] = ':';
            Field f = fields.get(i);
            System.arraycopy(f.body, f.start, out, at, f.end - f.start);
            at += f.end - f.start;
        }
        out[at++] = '}';
        return at == out.length ? out : Arrays.copyOf(out, at);
    }

    /** Set means not null, false, zero, or an empty string, array or object; absent is not set. */
    public static boolean truthy(byte @Nullable [] raw) {
        if (raw == null || raw.length == 0) {
            return false;
        }
        return switch (raw[0]) {
            case 't' -> true;
            case 'f', 'n' -> false;
            case '"' -> raw.length > 2;
            case '[', '{' -> hasContent(raw);
            default -> isNonZero(raw);
        };
    }

    private static boolean hasContent(byte[] raw) {
        for (int i = 1; i < raw.length - 1; i++) {
            if (!isSpace(raw[i])) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNonZero(byte[] number) {
        try {
            return Double.parseDouble(new String(number, StandardCharsets.US_ASCII)) != 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    static boolean isSpace(byte c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }
}
