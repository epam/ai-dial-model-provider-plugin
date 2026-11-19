package com.epam.aidial.provider.api;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.Pipe;

public sealed interface Body {

    record Bytes(Buffer buffer) implements Body {}

    /**
     * The pipe of the stream ({@code stream.pipe()}), made as soon as the provider has the stream: the pipe pauses it
     * and records its end or failure at once, so nothing is lost before the consumer attaches its destination, however
     * late. A failure means the stream broke: the consumer aborts the connection without a terminating chunk, so the
     * client sees a truncated stream rather than a clean end.
     */
    record Stream(Pipe<Buffer> pipe) implements Body {}
}
