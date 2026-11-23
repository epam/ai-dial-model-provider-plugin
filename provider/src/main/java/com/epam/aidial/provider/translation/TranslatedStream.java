package com.epam.aidial.provider.translation;

import com.epam.aidial.provider.upstream.StreamHandlers;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import io.vertx.core.streams.WriteStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The stream of the upstream, translated, as a stream. The upstream's pipe writes into {@link #sink()}; what the
 * {@link StreamTranslator} makes of it is read from {@link #output()}. The sink says it is full while the output has
 * more than {@link #HIGH_WATER_MARK} chunks the reader has not taken, so a slow client slows the upstream down, and the
 * output emits only what the reader has asked for. Whatever the upstream does, the output ends: a failure of the
 * upstream, or an end without the final event, is an {@code error} event first (the response is 200 already).
 * Everything runs on the context of the upstream's stream.
 */
final class TranslatedStream {

    /** Vert.x's default queue mark: a pipe pauses its source above it. */
    static final int HIGH_WATER_MARK = 16;

    private final SseDecoder decoder = new SseDecoder();
    private final StreamTranslator translator;
    private final ArrayDeque<Buffer> queue = new ArrayDeque<>();
    private final Sink sink = new Sink();
    private final Output output = new Output();
    private boolean ended;
    private boolean endEmitted;

    TranslatedStream(StreamTranslator translator) {
        this.translator = translator;
    }

    /** Where the upstream's body is written; its end, or {@link #fail}, ends the output. */
    WriteStream<Buffer> sink() {
        return sink;
    }

    ReadStream<Buffer> output() {
        return output;
    }

    /** The upstream's body broke. */
    void fail(Throwable cause) {
        if (!ended) {
            ended = true;
            add(translator.onFailure(cause));
            output.drain();
        }
    }

    private void add(List<String> events) {
        for (String event : events) {
            queue.add(Buffer.buffer(event.getBytes(StandardCharsets.UTF_8)));
        }
    }

    private final class Sink implements WriteStream<Buffer> {

        private @Nullable Handler<Void> drainHandler;

        @Override
        public Sink exceptionHandler(@Nullable Handler<Throwable> handler) {
            return this; // a write never fails
        }

        @Override
        public Future<Void> write(Buffer data) {
            if (!ended) {
                for (SseDecoder.SseEvent event : decoder.feed(data)) {
                    add(event.isHeartbeat() ? translator.onHeartbeat() : translator.onData(event.data()));
                }
                output.drain();
            }
            return Future.succeededFuture();
        }

        @Override
        public void write(Buffer data, Handler<AsyncResult<Void>> handler) {
            handler.handle(write(data));
        }

        @Override
        public void end(Handler<AsyncResult<Void>> handler) {
            if (!ended) {
                ended = true;
                add(translator.onEnd());
                output.drain();
            }
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
        public Sink drainHandler(@Nullable Handler<Void> handler) {
            this.drainHandler = handler;
            return this;
        }

        void drained() {
            Handler<Void> handler = drainHandler;
            if (handler != null && queue.size() < HIGH_WATER_MARK) {
                drainHandler = null;
                handler.handle(null);
            }
        }
    }

    private final class Output implements ReadStream<Buffer> {

        private final StreamHandlers out = new StreamHandlers();
        private boolean hasHandler;
        private boolean hasEndHandler;
        private long demand = Long.MAX_VALUE;
        private boolean draining;

        @Override
        public Output handler(@Nullable Handler<Buffer> handler) {
            out.handler(handler);
            hasHandler = handler != null;
            drain();
            return this;
        }

        @Override
        public Output exceptionHandler(@Nullable Handler<Throwable> handler) {
            out.exceptionHandler(handler);
            return this;
        }

        @Override
        public Output endHandler(@Nullable Handler<Void> handler) {
            out.endHandler(handler);
            hasEndHandler = handler != null;
            drain();
            return this;
        }

        @Override
        public Output pause() {
            demand = 0;
            return this;
        }

        @Override
        public Output resume() {
            return fetch(Long.MAX_VALUE);
        }

        @Override
        public Output fetch(long amount) {
            demand += amount;
            if (demand < 0) { // overflow
                demand = Long.MAX_VALUE;
            }
            drain();
            return this;
        }

        /** Emits what the reader has asked for, and the end after the last chunk; says the sink may write again. */
        void drain() {
            if (draining) {
                return; // a handler fetched or wrote: the loop goes on with it
            }
            draining = true;
            try {
                while (hasHandler && demand > 0 && !queue.isEmpty()) {
                    if (demand != Long.MAX_VALUE) {
                        demand--;
                    }
                    out.emit(queue.poll());
                }
                if (ended && queue.isEmpty() && !endEmitted && hasHandler && hasEndHandler) {
                    endEmitted = true;
                    out.end();
                }
            } finally {
                draining = false;
            }
            sink.drained();
        }
    }
}
