package com.epam.aidial.provider.core;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.vertx.core.buffer.Buffer;

// The ByteBuf accessors are deprecated in Vert.x 4.5 only in favour of Vert.x 5's BufferInternal.
@SuppressWarnings("deprecation")
public final class Buffers {

    private Buffers() {}

    /**
     * Returns the buffer's own array when it spans exactly one, else a copy: the array may be shared, never write it.
     */
    public static byte[] array(Buffer buffer) {
        ByteBuf b = buffer.getByteBuf();
        if (b.hasArray() && b.arrayOffset() == 0 && b.readerIndex() == 0 && b.array().length == buffer.length()) {
            return b.array();
        }
        return buffer.getBytes();
    }

    /** Wraps without a copy: Buffer.buffer(byte[]) copies. */
    public static Buffer wrap(byte[] bytes) {
        return Buffer.buffer(Unpooled.wrappedBuffer(bytes));
    }
}
