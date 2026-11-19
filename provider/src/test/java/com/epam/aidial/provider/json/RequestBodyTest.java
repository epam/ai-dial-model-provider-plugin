package com.epam.aidial.provider.json;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.epam.aidial.provider.api.InvalidRequestException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class RequestBodyTest {

    @Test
    void bodyWithoutBomIsKeptAsReceived() {
        byte[] received = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        RequestBody body = RequestBody.parse(received, null);
        assertSame(received, body.bytes());
        assertEquals("1", new String(body.fields().get("a"), StandardCharsets.UTF_8));
    }

    @Test
    void bomOfAnObjectIsSkipped() {
        byte[] received = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, '{', '"', 'a', '"', ':', '1', '}'};
        RequestBody body = RequestBody.parse(received, null);
        assertArrayEquals("{\"a\":1}".getBytes(StandardCharsets.UTF_8), body.bytes());
        assertEquals("1", new String(body.fields().get("a"), StandardCharsets.UTF_8));
    }

    @Test
    void bomOfAnotherValueIsSkippedToo() {
        byte[] received = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, '[', '1', ']'};
        InvalidRequestException e =
                assertThrows(InvalidRequestException.class, () -> RequestBody.parse(received, null));
        assertEquals("The request body must be a JSON object, not an array", e.getMessage());
    }
}
