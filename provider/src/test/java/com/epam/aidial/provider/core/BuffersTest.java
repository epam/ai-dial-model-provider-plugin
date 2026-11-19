package com.epam.aidial.provider.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;

class BuffersTest {

    @Test
    void anExactArrayIsShared() {
        byte[] a = {1, 2, 3};
        assertSame(a, Buffers.array(Buffers.wrap(a)));
    }

    @Test
    void anythingElseIsCopied() {
        Buffer grown = Buffer.buffer().appendBytes(new byte[] {1, 2, 3}); // Vert.x's aggregate has spare capacity
        assertArrayEquals(new byte[] {1, 2, 3}, Buffers.array(grown));
        byte[] a = {0, 1, 2, 3};
        byte[] slice = Buffers.array(Buffers.wrap(a).slice(1, 4));
        assertArrayEquals(new byte[] {1, 2, 3}, slice);
        assertNotSame(a, slice);
        assertArrayEquals(new byte[] {1, 2}, Buffers.array(Buffer.buffer("\u0001\u0002")));
    }
}
