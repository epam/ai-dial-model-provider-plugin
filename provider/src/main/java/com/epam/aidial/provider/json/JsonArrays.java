package com.epam.aidial.provider.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class JsonArrays {

    private JsonArrays() {}

    /** Null when raw is absent or not one array. */
    public static List<byte[]> elements(byte[] raw) {
        if (raw == null) {
            return null;
        }
        try (JsonParser parser = BodyFields.JSON.createParser(raw)) {
            if (parser.nextToken() != JsonToken.START_ARRAY) {
                return null;
            }
            List<byte[]> out = new ArrayList<>();
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                int start = (int) parser.currentTokenLocation().getByteOffset();
                parser.skipChildren();
                parser.finishToken();
                out.add(Arrays.copyOfRange(
                        raw, start, (int) parser.currentLocation().getByteOffset()));
            }
            return parser.nextToken() == null ? out : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    public static byte[] array(List<byte[]> values) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write('[');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                out.write(',');
            }
            out.writeBytes(values.get(i));
        }
        out.write(']');
        return out.toByteArray();
    }
}
