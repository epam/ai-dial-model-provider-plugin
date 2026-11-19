package com.epam.aidial.provider.openai;

import com.epam.aidial.provider.upstream.StreamHandlers;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A heartbeat can only fall between events, hence the re-chunking into whole events. An upstream failure fails the
 * stream, which the client sees truncated. Created paused; flow control is the upstream body's.
 */
final class SseEvents implements ReadStream<Buffer> {

    private static final Logger log = LoggerFactory.getLogger(SseEvents.class);

    private final Vertx vertx;
    private final ReadStream<Buffer> source;
    private final long heartbeatMillis;
    private final Buffer heartbeat = Buffer.buffer(": ping\n\n");
    private final Future<Void> aborted;
    private Buffer pending = Buffer.buffer();
    private int scanned; // pending is searched for an event end from here
    private final StreamHandlers out = new StreamHandlers();
    private boolean paused = true;
    private boolean finished;
    private long timer = -1;

    /**
     * @param heartbeatMillis 0 for no heartbeat
     */
    SseEvents(Vertx vertx, ReadStream<Buffer> source, long heartbeatMillis, Future<Void> aborted) {
        this.vertx = vertx;
        this.source = source;
        this.heartbeatMillis = heartbeatMillis;
        this.aborted = aborted;
        source.handler(this::onData);
        source.endHandler(v -> onEnd());
        source.exceptionHandler(this::onFailure);
    }

    private void onData(Buffer data) {
        if (finished) {
            return;
        }
        pending.appendBuffer(data);
        int end = lastEventEnd();
        if (end > 0) {
            Buffer events = pending.getBuffer(0, end);
            pending = pending.getBuffer(end, pending.length());
            scanned = 0;
            emit(events);
        }
    }

    /** The end of the last complete event in pending, 0 for none: after "\r\n\r\n" or "\n\n", the leftmost first. */
    private int lastEventEnd() {
        int last = 0;
        int n = pending.length();
        int i = scanned;
        while (i < n) {
            byte b = pending.getByte(i);
            if (b == '\r'
                    && i + 3 < n
                    && pending.getByte(i + 1) == '\n'
                    && pending.getByte(i + 2) == '\r'
                    && pending.getByte(i + 3) == '\n') {
                last = i + 4;
                i += 4;
            } else if (b == '\n' && i + 1 < n && pending.getByte(i + 1) == '\n') {
                last = i + 2;
                i += 2;
            } else {
                i++;
            }
        }
        scanned = Math.max(0, Math.max(last, n - 3)); // an end may straddle the next chunk
        return last;
    }

    private void emit(Buffer events) {
        stopTimer();
        out.emit(events);
        if (!paused) {
            startTimer();
        }
    }

    private void onEnd() {
        if (finished) {
            return;
        }
        if (pending.length() > 0) {
            emit(pending);
            pending = Buffer.buffer();
        }
        finished = true;
        stopTimer();
        out.end();
    }

    private void onFailure(Throwable e) {
        if (finished) {
            return;
        }
        finished = true;
        stopTimer();
        if (!aborted.isComplete()) {
            log.atWarn()
                    .addKeyValue("error", e.getMessage() == null ? e.toString() : e.getMessage())
                    .log("upstream failed mid-stream");
        }
        out.fail(e);
    }

    private void startTimer() {
        if (heartbeatMillis > 0 && timer < 0 && !finished) {
            timer = vertx.setTimer(heartbeatMillis, id -> {
                timer = -1;
                emit(heartbeat.copy());
            });
        }
    }

    private void stopTimer() {
        if (timer >= 0) {
            vertx.cancelTimer(timer);
            timer = -1;
        }
    }

    @Override
    public SseEvents handler(Handler<Buffer> handler) {
        out.handler(handler);
        return this;
    }

    @Override
    public SseEvents exceptionHandler(Handler<Throwable> handler) {
        out.exceptionHandler(handler);
        return this;
    }

    @Override
    public SseEvents endHandler(Handler<Void> handler) {
        out.endHandler(handler);
        return this;
    }

    @Override
    public SseEvents pause() {
        paused = true;
        stopTimer();
        source.pause();
        return this;
    }

    @Override
    public SseEvents resume() {
        paused = false;
        startTimer();
        source.resume();
        return this;
    }

    @Override
    public SseEvents fetch(long amount) {
        startTimer();
        source.fetch(amount);
        return this;
    }
}
