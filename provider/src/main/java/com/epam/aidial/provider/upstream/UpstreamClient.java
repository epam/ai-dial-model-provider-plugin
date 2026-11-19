package com.epam.aidial.provider.upstream;

import com.epam.aidial.provider.api.Body;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.streams.ReadStream;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class UpstreamClient {

    public static final String USER_AGENT = "aidial-model-provider/java";

    private static final Logger log = LoggerFactory.getLogger(UpstreamClient.class);
    private static final int CONNECT_TIMEOUT_MILLIS = 5000;
    private static final int UNCAPPED_POOL = 4096;

    private final Vertx vertx;
    private final HttpClient client;
    private final Limiter limiter;
    private final long readTimeoutMillis;
    private final Proxies proxies;

    public UpstreamClient(Vertx vertx, Proxies proxies, Limits limits) {
        this.vertx = vertx;
        this.client = vertx.createHttpClient(new HttpClientOptions()
                .setProtocolVersion(HttpVersion.HTTP_1_1)
                // The cap is the limiter's; a smaller pool would queue calls inside Vert.x, out of its sight.
                .setMaxPoolSize(limits.maxConnections().orElse(UNCAPPED_POOL))
                .setKeepAliveTimeout(90)
                .setConnectTimeout(CONNECT_TIMEOUT_MILLIS)
                .setSslHandshakeTimeout(CONNECT_TIMEOUT_MILLIS)
                .setSslHandshakeTimeoutUnit(TimeUnit.MILLISECONDS)
                .setForceSni(true));
        this.limiter = Limiter.of(vertx, limits);
        this.readTimeoutMillis = limits.readTimeout().toMillis();
        this.proxies = proxies;
    }

    /** A call still in flight fails. */
    public Future<Void> close() {
        return client.close();
    }

    /**
     * Fails with {@link PoolExhaustedException}, {@link ClientGoneException}, {@link UpstreamTimeoutException} (no
     * response headers within the read timeout), or the transport's error.
     *
     * @param body null for none
     */
    public Future<UpstreamResponse> send(
            HttpMethod method, String url, MultiMap headers, Buffer body, Future<Void> aborted) {
        if (aborted.isComplete()) {
            return Future.failedFuture(new ClientGoneException());
        }
        Future<Limiter.Slot> slot = limiter.acquire(aborted);
        // As core/Unsent: the function below outlives the write, so the body is taken from a holder.
        Buffer[] pending = {body};
        return slot.compose(s -> {
            Buffer b = pending[0];
            pending[0] = null;
            return call(method, url, headers, b, aborted, s);
        });
    }

    private Future<UpstreamResponse> call(
            HttpMethod method, String url, MultiMap headers, Buffer body, Future<Void> aborted, Limiter.Slot slot) {
        AtomicBoolean released = new AtomicBoolean();
        Runnable done = () -> {
            if (released.compareAndSet(false, true)) {
                slot.release();
            }
        };
        RequestOptions options;
        try {
            options = requestOptions(method, url, headers);
        } catch (IllegalArgumentException e) {
            done.run();
            return Future.failedFuture(e);
        }
        Promise<UpstreamResponse> result = Promise.promise();
        client.request(options).onComplete(ar -> {
            if (ar.failed()) {
                done.run();
                result.fail(aborted.isComplete() ? new ClientGoneException() : ar.cause());
            } else {
                exchange(ar.result(), method, body, aborted, done, result);
            }
        });
        return result.future();
    }

    /**
     * @throws IllegalArgumentException when the URL or a header value is one HTTP does not allow; its message names
     *     neither, as they may hold a key or token
     */
    private RequestOptions requestOptions(HttpMethod method, String url, MultiMap headers) {
        RequestOptions options;
        try {
            options = new RequestOptions().setMethod(method).setAbsoluteURI(url).setFollowRedirects(false);
            options.setProxyOptions(
                    proxies.forRequest(Boolean.TRUE.equals(options.isSsl()), options.getHost(), options.getPort()));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("the upstream URL is not one the service can request");
        }
        try {
            if (!headers.contains("User-Agent")) {
                options.putHeader("User-Agent", USER_AGENT);
            }
            headers.forEach(options::addHeader);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("an upstream request header has a value HTTP does not allow");
        }
        return options;
    }

    private void exchange(
            HttpClientRequest request,
            HttpMethod method,
            Buffer body,
            Future<Void> aborted,
            Runnable done,
            Promise<UpstreamResponse> result) {
        AtomicBoolean timedOut = new AtomicBoolean();
        long timer = readTimeoutMillis > 0
                ? vertx.setTimer(readTimeoutMillis, id -> {
                    timedOut.set(true);
                    request.reset();
                })
                : -1;
        aborted.onComplete(v -> request.reset());
        Future<HttpClientResponse> sent;
        if (body == null) {
            if (method == HttpMethod.POST
                    || method == HttpMethod.PUT
                    || method == HttpMethod.PATCH
                    || method == HttpMethod.DELETE) {
                request.putHeader("Content-Length", "0");
            }
            sent = request.send();
        } else {
            sent = request.send(body);
        }
        sent.onComplete(response -> {
            if (timer >= 0) {
                vertx.cancelTimer(timer);
            }
            if (response.failed()) {
                done.run();
                result.fail(
                        timedOut.get()
                                ? new UpstreamTimeoutException()
                                : aborted.isComplete() ? new ClientGoneException() : response.cause());
                return;
            }
            IdleReadStream stream = new IdleReadStream(vertx, request, response.result(), readTimeoutMillis, done);
            result.complete(new UpstreamResponse(
                    response.result().statusCode(), response.result().headers(), stream, aborted));
        });
    }

    /** The body is consumed exactly once: read whole, relayed or discarded. */
    public static final class UpstreamResponse {

        private final int status;
        private final MultiMap headers;
        private final IdleReadStream stream;
        private final Future<Void> aborted;

        UpstreamResponse(int status, MultiMap headers, IdleReadStream stream, Future<Void> aborted) {
            this.status = status;
            this.headers = headers;
            this.stream = stream;
            this.aborted = aborted;
        }

        public int status() {
            return status;
        }

        public MultiMap headers() {
            return headers;
        }

        public boolean isSuccess() {
            return isSuccess(status);
        }

        public static boolean isSuccess(int status) {
            return status >= 200 && status <= 299;
        }

        /** Fails on a cut or stalled body: nothing of it is relayed. */
        public Future<Buffer> body() {
            Promise<Buffer> promise = Promise.promise();
            Buffer whole = Buffer.buffer();
            stream.handler(whole::appendBuffer)
                    .endHandler(v -> promise.complete(whole))
                    .exceptionHandler(e -> promise.fail(aborted.isComplete() ? new ClientGoneException() : e));
            stream.resume();
            return promise.future();
        }

        /** A break before the end is logged, unless the client went first. */
        public Body.Stream relay() {
            stream.failureListener(e -> {
                if (!aborted.isComplete()) {
                    log.warn("upstream failed mid-stream: error={}", e.toString());
                }
            });
            return new Body.Stream(stream.pipe());
        }

        /** Paused until the caller resumes it. */
        public ReadStream<Buffer> stream() {
            return stream;
        }

        public void discard() {
            stream.discard();
        }
    }
}
