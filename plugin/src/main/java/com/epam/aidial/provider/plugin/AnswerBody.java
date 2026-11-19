package com.epam.aidial.provider.plugin;

import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import io.vertx.core.streams.WriteStream;
import java.util.ArrayDeque;

/**
 * Everything on the request's context. Nothing flows before DIAL Core sets a handler or while it pauses, and nothing
 * is emitted from within DIAL Core's own calls ({@code handler}, {@code resume}, {@code fetch}), which only schedule
 * a drain. A chunk written while nothing waits goes straight through, in the writer's task.
 */
final class AnswerBody implements ReadStream<Buffer> {

    // Vert.x's default inbound queue mark: the pipe pauses the upstream above it
    private static final int HIGH_WATER_MARK = 16;
    private static final Object END = new Object();

    private record Failure(Throwable error) {}

    private final Context context;
    private final ArrayDeque<Object> queue = new ArrayDeque<>(); // buffers, then END or a Failure
    private final Sink sink = new Sink();
    private Handler<Buffer> handler;
    private Handler<Void> endHandler;
    private Handler<Throwable> exceptionHandler;
    private long demand = Long.MAX_VALUE;
    private boolean emitting;
    private boolean scheduled;
    private boolean cancelled;

    AnswerBody(Context context) {
        this.context = context;
    }

    WriteStream<Buffer> sink() {
        return sink;
    }

    /** Queued behind the chunks already written. */
    void fail(Throwable error) {
        queue.add(new Failure(error));
        emit();
    }

    /** DIAL Core stopped reading: what is queued or comes later is dropped. */
    void cancel() {
        cancelled = true;
        queue.clear();
    }

    @Override
    public AnswerBody handler(Handler<Buffer> handler) {
        this.handler = handler;
        schedule();
        return this;
    }

    @Override
    public AnswerBody endHandler(Handler<Void> endHandler) {
        this.endHandler = endHandler;
        return this;
    }

    @Override
    public AnswerBody exceptionHandler(Handler<Throwable> handler) {
        this.exceptionHandler = handler;
        return this;
    }

    @Override
    public AnswerBody pause() {
        demand = 0;
        return this;
    }

    @Override
    public AnswerBody resume() {
        return fetch(Long.MAX_VALUE);
    }

    @Override
    public AnswerBody fetch(long amount) {
        demand += amount;
        if (demand < 0) { // overflow
            demand = Long.MAX_VALUE;
        }
        schedule();
        return this;
    }

    private void schedule() {
        if (!scheduled && !queue.isEmpty()) {
            scheduled = true;
            context.runOnContext(v -> {
                scheduled = false;
                emit();
            });
        }
    }

    private void emit() {
        if (cancelled) {
            queue.clear();
            return;
        }
        if (emitting) {
            return; // a handler wrote or fetched: the loop below goes on with it
        }
        emitting = true;
        try {
            while (handler != null && demand > 0 && !queue.isEmpty()) {
                Object next = queue.poll();
                if (next instanceof Buffer chunk) {
                    if (demand != Long.MAX_VALUE) {
                        demand--;
                    }
                    handler.handle(chunk);
                } else if (next == END) {
                    if (endHandler != null) {
                        endHandler.handle(null);
                    }
                } else if (next instanceof Failure failure && exceptionHandler != null) {
                    exceptionHandler.handle(failure.error());
                }
            }
        } finally {
            emitting = false;
        }
        Handler<Void> drain = sink.drainHandler;
        if (drain != null && queue.size() < HIGH_WATER_MARK) {
            sink.drainHandler = null;
            drain.handle(null);
        }
    }

    private final class Sink implements WriteStream<Buffer> {

        private Handler<Void> drainHandler;

        @Override
        public Sink exceptionHandler(Handler<Throwable> handler) {
            return this; // a write never fails
        }

        @Override
        public Future<Void> write(Buffer data) {
            queue.add(data);
            emit();
            return Future.succeededFuture();
        }

        @Override
        public void write(Buffer data, Handler<AsyncResult<Void>> handler) {
            handler.handle(write(data));
        }

        @Override
        public void end(Handler<AsyncResult<Void>> handler) {
            queue.add(END);
            emit();
            handler.handle(Future.succeededFuture());
        }

        @Override
        public Sink setWriteQueueMaxSize(int maxSize) {
            return this;
        }

        @Override
        public boolean writeQueueFull() {
            return queue.size() >= HIGH_WATER_MARK;
        }

        @Override
        public Sink drainHandler(Handler<Void> handler) {
            this.drainHandler = handler;
            return this;
        }
    }
}
