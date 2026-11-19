package com.epam.aidial.provider.anthropic;

import io.vertx.core.buffer.Buffer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.CRC32;

/**
 * The limits on the headers (128 KiB) and the payload (16 MiB) are the AWS SDKs'. What a caller makes of a partial
 * frame left at the end ({@link #partial}) is its own rule: the passthrough fails the stream, the AWS SDKs and the
 * Anthropic SDK drop it. Not thread-safe.
 */
final class EventStreamFrames {

    private static final long MAX_HEADERS = 128 << 10;
    private static final long MAX_PAYLOAD = 16 << 20;

    private static final int PRELUDE_BYTES = 12;
    private static final int HEADERS_LENGTH_AT = 4;
    private static final int PRELUDE_CRC_AT = 8;
    private static final int CRC_BYTES = 4;
    private static final int FRAME_OVERHEAD_BYTES = PRELUDE_BYTES + CRC_BYTES;

    private static final int TRUE = 0;
    private static final int FALSE = 1;
    private static final int BYTE = 2;
    private static final int SHORT = 3;
    private static final int INTEGER = 4;
    private static final int LONG = 5;
    private static final int BYTES = 6;
    private static final int STRING = 7;
    private static final int TIMESTAMP = 8;
    private static final int UUID = 9;

    static final class Malformed extends RuntimeException {

        Malformed(String message) {
            super(message);
        }

        Malformed(String message, Throwable cause) {
            super(message, cause);
        }
    }

    record Message(Map<String, String> headers, byte[] payload) {}

    private Buffer pending = Buffer.buffer();

    /** The messages before a malformed frame are handed over before {@link Malformed} is thrown. */
    void feed(Buffer data, Consumer<Message> each) {
        pending.appendBuffer(data);
        int at = 0;
        try {
            while (pending.length() - at >= PRELUDE_BYTES) {
                long total = pending.getUnsignedInt(at);
                long headersLength = pending.getUnsignedInt(at + HEADERS_LENGTH_AT);
                checksum(pending.getUnsignedInt(at + PRELUDE_CRC_AT), crc(pending, at, at + PRELUDE_CRC_AT));
                if (headersLength > MAX_HEADERS) {
                    throw new Malformed(
                            "Header length of " + headersLength + " exceeded the maximum of " + MAX_HEADERS);
                }
                long payloadLength = total - FRAME_OVERHEAD_BYTES - headersLength;
                if (payloadLength > MAX_PAYLOAD) {
                    throw new Malformed(
                            "Payload length of " + payloadLength + " exceeded the maximum of " + MAX_PAYLOAD);
                }
                if (total < FRAME_OVERHEAD_BYTES || payloadLength < 0) {
                    throw new Malformed("event stream: invalid message length");
                }
                if (pending.length() - at < total) {
                    break;
                }
                int end = at + (int) total;
                int crcAt = end - CRC_BYTES;
                checksum(pending.getUnsignedInt(crcAt), crc(pending, at, crcAt));
                int headersStart = at + PRELUDE_BYTES;
                int headersEnd = headersStart + (int) headersLength;
                Message m =
                        new Message(headers(pending, headersStart, headersEnd), pending.getBytes(headersEnd, crcAt));
                at = end;
                each.accept(m);
            }
        } finally {
            pending = at == pending.length() ? Buffer.buffer() : pending.getBuffer(at, pending.length());
        }
    }

    boolean partial() {
        return pending.length() > 0;
    }

    static Map<String, String> headers(Buffer frame, int start, int end) {
        Map<String, String> out = new LinkedHashMap<>();
        int h = start;
        while (h < end) {
            int n = frame.getUnsignedByte(h);
            if (end - h < n + 2) {
                throw new Malformed("event stream: truncated header");
            }
            String name = frame.getString(h + 1, h + 1 + n, "UTF-8");
            int type = frame.getUnsignedByte(h + 1 + n);
            h += n + 2;
            int size =
                    switch (type) {
                        case TRUE, FALSE -> 0;
                        case BYTE -> 1;
                        case SHORT -> 2;
                        case INTEGER -> 4;
                        case LONG, TIMESTAMP -> 8;
                        case UUID -> 16;
                        case BYTES, STRING -> {
                            if (end - h < 2) {
                                throw new Malformed("event stream: truncated header");
                            }
                            yield 2 + frame.getUnsignedShort(h);
                        }
                        default -> throw new Malformed("event stream: unknown header type");
                    };
            if (end - h < size) {
                throw new Malformed("event stream: truncated header");
            }
            if (type == STRING) {
                out.put(name, frame.getString(h + 2, h + size, "UTF-8"));
            }
            h += size;
        }
        return out;
    }

    private static void checksum(long expected, long calculated) {
        if (expected != calculated) {
            throw new Malformed(
                    String.format("Checksum mismatch: expected 0x%08x, calculated 0x%08x", expected, calculated));
        }
    }

    private static long crc(Buffer b, int start, int end) {
        CRC32 crc = new CRC32();
        crc.update(b.getBytes(start, end));
        return crc.getValue();
    }
}
