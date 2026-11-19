package com.epam.aidial.provider.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.epam.aidial.provider.testing.AwsFrames;
import io.vertx.core.buffer.Buffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Test;

class EventStreamDecoderTest {

    private static byte[] event(String type, String payload) {
        return AwsFrames.event(type, payload);
    }

    private static long crc(byte[] b) {
        CRC32 c = new CRC32();
        c.update(b);
        return c.getValue();
    }

    @Test
    void framesSplitAcrossReads() {
        Buffer all = Buffer.buffer().appendBytes(event("a", "{\"x\":1}")).appendBytes(event("b", "{}"));
        EventStreamFrames frames = new EventStreamFrames();
        List<EventStreamFrames.Message> got = new ArrayList<>();
        for (int i = 0; i < all.length(); i += 7) {
            frames.feed(all.getBuffer(i, Math.min(all.length(), i + 7)), got::add);
        }
        assertEquals(2, got.size());
        assertEquals("a", got.get(0).headers().get(":event-type"));
        assertEquals("{\"x\":1}", new String(got.get(0).payload(), StandardCharsets.UTF_8));
        assertEquals("event", got.get(1).headers().get(":message-type"));
        assertFalse(frames.partial());
    }

    /** A partial frame left at the end is the caller's to judge: the tolerant readers drop it. */
    @Test
    void truncatedLastFrameLeftPartial() {
        byte[] second = event("b", "{}");
        Buffer data = Buffer.buffer().appendBytes(event("a", "{}")).appendBytes(second, 0, second.length / 2);
        EventStreamFrames frames = new EventStreamFrames();
        List<EventStreamFrames.Message> got = new ArrayList<>();
        frames.feed(data, got::add);
        assertEquals(1, got.size());
        assertTrue(frames.partial());
    }

    /** A message checksum mismatch fails with the checksum message, after the messages before it are handed over. */
    @Test
    void checksumMismatch() {
        byte[] bad = event("b", "{}");
        bad[bad.length - 1] ^= (byte) 0xff;
        Buffer data = Buffer.buffer().appendBytes(event("a", "{}")).appendBytes(bad);
        List<EventStreamFrames.Message> got = new ArrayList<>();
        EventStreamFrames.Malformed e =
                assertThrows(EventStreamFrames.Malformed.class, () -> new EventStreamFrames().feed(data, got::add));
        assertEquals(1, got.size());
        assertEquals("Checksum mismatch: expected 0x1c2cccf4, calculated 0x1c2ccc0b", e.getMessage());
    }

    @Test
    void preludeChecksumMismatch() {
        byte[] bad = event("a", "{}");
        bad[8] ^= 1;
        EventStreamFrames.Malformed e = assertThrows(
                EventStreamFrames.Malformed.class, () -> new EventStreamFrames().feed(Buffer.buffer(bad), m -> {}));
        assertEquals("Checksum mismatch: expected 0xb999a6e0, calculated 0xb899a6e0", e.getMessage());
    }

    @Test
    void headersOverTheLimit() {
        Buffer b = Buffer.buffer().appendInt(16 + 200_000).appendInt(200_000);
        b.appendInt((int) crc(b.getBytes()));
        EventStreamFrames.Malformed e =
                assertThrows(EventStreamFrames.Malformed.class, () -> new EventStreamFrames().feed(b, m -> {}));
        assertEquals("Header length of 200000 exceeded the maximum of 131072", e.getMessage());
    }
}
