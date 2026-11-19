package com.epam.aidial.provider.upstream;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.streams.ReadStream;
import java.util.function.Consumer;

/**
 * The idle timer runs only while the stream is not paused, so time spent writing to a slow client does not count. A
 * timeout resets the upstream request, closing its connection. {@code done} runs once: at the end of the body, on its
 * failure, or when it is discarded. Created paused.
 */
final class IdleReadStream implements ReadStream<Buffer> {

    private final Vertx vertx;
    private final HttpClientRequest request;
    private final HttpClientResponse source;
    private final long idleMillis;
    private final Runnable done;
    private Consumer<Throwable> failureListener = e -> {};
    private final StreamHandlers out = new StreamHandlers();
    private boolean paused = true;
    private boolean finished;
    private long timer = -1;

    IdleReadStream(Vertx vertx, HttpClientRequest request, HttpClientResponse source, long idleMillis, Runnable done) {
        this.vertx = vertx;
        this.request = request;
        this.source = source;
        this.idleMillis = idleMillis;
        this.done = done;
        source.pause();
        source.handler(this::onData);
        source.endHandler(v -> onEnd());
        source.exceptionHandler(this::onFailure);
    }

    /** Runs before the failure is delivered downstream. */
    void failureListener(Consumer<Throwable> listener) {
        this.failureListener = listener;
    }

    @Override
    public IdleReadStream handler(Handler<Buffer> handler) {
        out.handler(handler);
        return this;
    }

    @Override
    public IdleReadStream exceptionHandler(Handler<Throwable> handler) {
        out.exceptionHandler(handler);
        return this;
    }

    @Override
    public IdleReadStream endHandler(Handler<Void> handler) {
        out.endHandler(handler);
        return this;
    }

    @Override
    public IdleReadStream pause() {
        paused = true;
        stopTimer();
        source.pause();
        return this;
    }

    @Override
    public IdleReadStream resume() {
        paused = false;
        startTimer();
        source.resume();
        return this;
    }

    @Override
    public IdleReadStream fetch(long amount) {
        startTimer();
        source.fetch(amount);
        return this;
    }

    void discard() {
        if (!finished) {
            finished = true;
            stopTimer();
            request.reset();
            done.run();
        }
    }

    private void onData(Buffer data) {
        if (finished) {
            return;
        }
        stopTimer();
        out.emit(data);
        if (!paused) {
            startTimer();
        }
    }

    private void onEnd() {
        if (finished) {
            return;
        }
        finished = true;
        stopTimer();
        done.run();
        out.end();
    }

    private void onFailure(Throwable cause) {
        if (finished) {
            return;
        }
        finished = true;
        stopTimer();
        request.reset(); // a stream the upstream broke: its connection is not reused
        done.run();
        failureListener.accept(cause);
        out.fail(cause);
    }

    private void startTimer() {
        if (idleMillis > 0 && timer < 0 && !finished) {
            timer = vertx.setTimer(idleMillis, id -> {
                timer = -1;
                onFailure(new UpstreamTimeoutException());
            });
        }
    }

    private void stopTimer() {
        if (timer >= 0) {
            vertx.cancelTimer(timer);
            timer = -1;
        }
    }
}
