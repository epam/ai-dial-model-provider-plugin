package com.epam.aidial.provider.core;

import io.vertx.core.buffer.Buffer;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/**
 * The body of an upstream request until the send takes it. Vert.x keeps the function of a compose, and whatever it
 * captured, until the composed future completes: for an upstream call, until the answer's headers arrive. The functions
 * of a chain that sends a body therefore capture this holder, never the body, nor a request or a parse that holds it,
 * and the send takes the body out: it is released once written.
 */
public final class Unsent {

    private final AtomicReference<byte[]> body;

    /**
     * @param body null for a request without one
     */
    public Unsent(@Nullable byte[] body) {
        this.body = new AtomicReference<>(body);
    }

    /** Null once {@link #take} has handed the body to the send. */
    public @Nullable byte[] peek() {
        return body.get();
    }

    /** Leaves this holder empty, so the body is released once written. */
    public @Nullable Buffer take() {
        byte[] taken = body.getAndSet(null);
        return taken == null ? null : Buffers.wrap(taken);
    }
}
