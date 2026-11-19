package com.epam.aidial.provider.upstream;

import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import org.jspecify.annotations.Nullable;

public final class StreamHandlers {

    private @Nullable Handler<Buffer> handler;
    private @Nullable Handler<Throwable> exceptionHandler;
    private @Nullable Handler<Void> endHandler;

    public void handler(@Nullable Handler<Buffer> handler) {
        this.handler = handler;
    }

    public void exceptionHandler(@Nullable Handler<Throwable> handler) {
        this.exceptionHandler = handler;
    }

    public void endHandler(@Nullable Handler<Void> handler) {
        this.endHandler = handler;
    }

    public void emit(Buffer data) {
        if (handler != null) {
            handler.handle(data);
        }
    }

    public void end() {
        if (endHandler != null) {
            endHandler.handle(null);
        }
    }

    public void fail(Throwable cause) {
        if (exceptionHandler != null) {
            exceptionHandler.handle(cause);
        }
    }
}
