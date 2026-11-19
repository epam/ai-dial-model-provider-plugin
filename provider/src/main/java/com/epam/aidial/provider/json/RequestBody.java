package com.epam.aidial.provider.json;

import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/**
 * The ranges of {@code fields} index {@code bytes}, the received body without a leading UTF-8 BOM.
 *
 * @param bytes what is forwarded when no member changes
 */
public record RequestBody(byte[] bytes, BodyFields fields) {

    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    public static RequestBody parse(byte[] received, BodyFields.@Nullable MemberScanner scanner) {
        boolean bom = received.length >= BOM.length && Arrays.equals(received, 0, BOM.length, BOM, 0, BOM.length);
        byte[] bytes = bom ? Arrays.copyOfRange(received, BOM.length, received.length) : received;
        return new RequestBody(bytes, BodyFields.parse(bytes, scanner));
    }
}
