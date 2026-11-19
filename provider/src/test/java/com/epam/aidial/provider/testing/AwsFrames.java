package com.epam.aidial.provider.testing;

import io.vertx.core.buffer.Buffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;

public final class AwsFrames {

    private AwsFrames() {}

    private static byte[] frame(Map<String, String> headers, byte[] payload) {
        Buffer h = Buffer.buffer();
        headers.forEach((k, v) -> {
            byte[] name = k.getBytes(StandardCharsets.UTF_8);
            byte[] value = v.getBytes(StandardCharsets.UTF_8);
            h.appendByte((byte) name.length)
                    .appendBytes(name)
                    .appendByte((byte) 7)
                    .appendShort((short) value.length)
                    .appendBytes(value);
        });
        Buffer b = Buffer.buffer().appendInt(16 + h.length() + payload.length).appendInt(h.length());
        b.appendInt((int) crc(b.getBytes()));
        b.appendBuffer(h).appendBytes(payload);
        b.appendInt((int) crc(b.getBytes()));
        return b.getBytes();
    }

    public static byte[] event(String type, String payload) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(":message-type", "event");
        headers.put(":event-type", type);
        headers.put(":content-type", "application/json");
        return frame(headers, payload.getBytes(StandardCharsets.UTF_8));
    }

    private static long crc(byte[] b) {
        CRC32 c = new CRC32();
        c.update(b);
        return c.getValue();
    }
}
