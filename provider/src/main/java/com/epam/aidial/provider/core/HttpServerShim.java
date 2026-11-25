package com.epam.aidial.provider.core;

import com.epam.aidial.provider.api.AdapterRequest;
import com.epam.aidial.provider.api.AdapterResponse;
import com.epam.aidial.provider.api.Body;
import com.epam.aidial.provider.api.Handler;
import io.netty.buffer.Unpooled;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.streams.Pipe;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Shared by every server verticle of a process, so it holds no per-event-loop state. */
public final class HttpServerShim implements io.vertx.core.Handler<HttpServerRequest> {

    private static final Logger log = LoggerFactory.getLogger(HttpServerShim.class);

    /** DIAL Core's default resources.maxSize: a larger declared length allocates nothing up front. */
    private static final int PRESIZED_MAX = 64 << 20;

    /** A complete body's connection errors are the response's: its closeHandler aborts the exchange. */
    private static final io.vertx.core.Handler<Throwable> IGNORED = e -> {};

    private final Handler provider;
    private final Handler core;
    private final AtomicInteger inFlight = new AtomicInteger();
    private volatile boolean draining;

    /** For a provider that does not call DIAL Core back. */
    public HttpServerShim(Handler provider) {
        this(provider, AdapterRequest.NO_CORE);
    }

    /**
     * @param core DIAL Core's create operations for the requests served (see {@link AdapterRequest#core()})
     */
    public HttpServerShim(Handler provider, Handler core) {
        this.provider = provider;
        this.core = core;
    }

    public int inFlight() {
        return inFlight.get();
    }

    /** Every response closes its connection, so pooled clients open new ones elsewhere instead of keeping us busy. */
    public void drain() {
        draining = true;
    }

    @Override
    public void handle(HttpServerRequest request) {
        Exchange exchange = new Exchange(request);
        // A body that cannot be read, cut or malformed: Vert.x closes the connection, and the provider never runs.
        body(request).onSuccess(exchange::dispatch);
    }

    /**
     * The body; one declared up to PRESIZED_MAX lands in an array of exactly its length, which routes read and forward
     * without another copy (Buffers). Vert.x's own aggregate grows by doubling and is copied again by each reader.
     */
    @SuppressWarnings("deprecation") // Buffer.buffer(ByteBuf): see Buffers
    private static Future<Buffer> body(HttpServerRequest request) {
        long declared;
        try {
            declared = Long.parseLong(request.getHeader("Content-Length"));
        } catch (NumberFormatException e) { // none, or chunked
            return request.body();
        }
        if (declared < 0 || declared > PRESIZED_MAX) {
            return request.body();
        }
        Buffer body =
                Buffer.buffer(Unpooled.wrappedBuffer(new byte[(int) declared]).clear());
        Promise<Buffer> read = Promise.promise();
        request.handler(chunk -> {
            if (body.length() + chunk.length() <= declared) { // Netty frames by Content-Length: always
                body.appendBuffer(chunk);
            }
        });
        request.exceptionHandler(read::tryFail);
        request.endHandler(v -> {
            // The handlers hold the body until the exchange ends: dropped, the body lives only as long as the route
            // uses it, not while the answer streams.
            request.handler(null).endHandler(null).exceptionHandler(IGNORED);
            if (body.length() == declared) {
                read.tryComplete(body);
            } else {
                request.connection().close();
            }
        });
        return read.future();
    }

    private final class Exchange {

        private final HttpServerRequest request;
        private final HttpServerResponse response;
        private final Promise<Void> aborted = Promise.promise();
        private final AtomicBoolean finished = new AtomicBoolean();

        Exchange(HttpServerRequest request) {
            this.request = request;
            this.response = request.response();
            inFlight.incrementAndGet();
            response.endHandler(v -> finish());
            response.closeHandler(v -> {
                abort();
                finish();
            });
        }

        void dispatch(Buffer body) {
            AdapterRequest adapterRequest = new AdapterRequest(
                    request.method(), request.path(), request.query(), request.headers(), body, aborted.future(), core);
            Answers.call(provider, adapterRequest).onComplete(ar -> {
                if (response.closed()) {
                    return; // the client is gone; the provider saw it through aborted
                }
                write(ar.failed() ? Answers.internalError(request.path(), ar.cause()) : ar.result());
            });
        }

        private void write(AdapterResponse result) {
            try {
                response.setStatusCode(result.status());
                Answers.copyForwarded(result.headers(), response.headers());
                if (draining) {
                    response.putHeader("Connection", "close");
                }
                switch (result.body()) {
                    case Body.Bytes bytes -> response.end(bytes.buffer());
                    case Body.Stream stream -> {
                        // piped before anything is written: the stream is handed over paused
                        Pipe<Buffer> pipe = stream.pipe().endOnFailure(false);
                        response.setChunked(true);
                        response.writeHead();
                        pipe.to(response).onFailure(this::streamFailed);
                    }
                }
            } catch (RuntimeException e) {
                log.error("response failed: path={}", request.path(), e);
                if (!response.headWritten()) {
                    response.headers().clear();
                    response.setChunked(false);
                    write(AdapterResponse.internalServerError());
                } else {
                    response.reset();
                    abort();
                    finish(); // a reset response gets neither its end nor its close handler
                }
            }
        }

        private void streamFailed(Throwable e) {
            // The upstream broke, or the client did (gone while writes were queued, before Vert.x saw the connection
            // close; the pipe then resumes the upstream into nothing). Either way the upstream call is over. Which side
            // broke only the provider knows: it logs an upstream failure itself.
            log.debug("stream failed: path={}, error={}", request.path(), e.toString());
            abort();
            if (!response.closed()) {
                // No terminating chunk: the client sees a truncated stream, not a clean end.
                response.reset();
            }
            finish(); // a reset response gets neither its end nor its close handler
        }

        private void abort() {
            if (!response.ended()) {
                aborted.tryComplete();
            }
        }

        private void finish() {
            if (finished.compareAndSet(false, true)) {
                inFlight.decrementAndGet();
                if (draining) {
                    // Vert.x keeps the connection open whatever the response's Connection header says.
                    request.connection().close();
                }
            }
        }
    }
}
