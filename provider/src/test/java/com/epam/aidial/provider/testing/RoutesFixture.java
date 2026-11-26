package com.epam.aidial.provider.testing;

import static com.epam.aidial.provider.testing.MockUpstream.await;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.epam.aidial.provider.api.AdapterResponse;
import com.epam.aidial.provider.api.Body;
import com.epam.aidial.provider.core.Routes;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.streams.WriteStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * What the tests of a route table share: a Vert.x instance and a {@link MockUpstream} per test (closed after it), the
 * request helpers and the answer readers. Register it as an instance field with {@code @RegisterExtension}; it is ready
 * before the test class's own {@code @BeforeEach} methods.
 */
public final class RoutesFixture implements BeforeEachCallback, AfterEachCallback {

    /** What a route table's clock reads: the same second in every test, so a cache expiry is a literal. */
    public static final Clock CLOCK = Clock.fixed(Instant.ofEpochSecond(1000), ZoneOffset.UTC);

    /** What a streamed answer delivered, and whether the stream broke instead of ending. */
    public record Collected(Buffer received, boolean failed) {}

    private Vertx vertx;
    private MockUpstream mock;

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        vertx = Vertx.vertx();
        mock = new MockUpstream(vertx);
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        await(vertx.close());
    }

    public Vertx vertx() {
        return vertx;
    }

    public MockUpstream mock() {
        return mock;
    }

    public AdapterResponse send(Routes routes, HttpMethod method, String pathAndQuery, byte[] body, MultiMap headers)
            throws Exception {
        int q = pathAndQuery.indexOf('?');
        String path = q < 0 ? pathAndQuery : pathAndQuery.substring(0, q);
        String query = q < 0 ? null : pathAndQuery.substring(q + 1);
        return await(routes.handle(Requests.of(
                method,
                path,
                query,
                headers,
                Buffer.buffer(body),
                Promise.<Void>promise().future())));
    }

    /** Any method; the body may be null, {@code headers} are name and value pairs. */
    public AdapterResponse send(
            Routes routes, HttpMethod method, String pathAndQuery, @Nullable String body, String... headers)
            throws Exception {
        MultiMap h = MultiMap.caseInsensitiveMultiMap();
        for (int i = 0; i < headers.length; i += 2) {
            h.add(headers[i], headers[i + 1]);
        }
        return send(
                routes, method, pathAndQuery, body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8), h);
    }

    /** A POST; {@code headers} are name and value pairs. */
    public AdapterResponse post(Routes routes, String path, String body, String... headers) throws Exception {
        return post(routes, path, body.getBytes(StandardCharsets.UTF_8), headers);
    }

    public AdapterResponse post(Routes routes, String path, byte[] body, String... headers) throws Exception {
        MultiMap h = MultiMap.caseInsensitiveMultiMap();
        for (int i = 0; i < headers.length; i += 2) {
            h.add(headers[i], headers[i + 1]);
        }
        return send(routes, HttpMethod.POST, path, body, h);
    }

    public AdapterResponse post(Routes routes, String path, String body, Map<String, String> headers) throws Exception {
        return post(routes, path, body.getBytes(StandardCharsets.UTF_8), headers);
    }

    public AdapterResponse post(Routes routes, String path, byte[] body, Map<String, String> headers) throws Exception {
        MultiMap h = MultiMap.caseInsensitiveMultiMap();
        headers.forEach(h::add);
        return send(routes, HttpMethod.POST, path, body, h);
    }

    /** The whole answer body as text; a stream that breaks fails the test. */
    public static String read(AdapterResponse response) throws Exception {
        if (response.body() instanceof Body.Bytes bytes) {
            return bytes.buffer().toString(StandardCharsets.UTF_8);
        }
        Collected collected = collect(response);
        assertFalse(collected.failed(), "the answer's stream broke");
        return collected.received().toString(StandardCharsets.UTF_8);
    }

    /** Pipes a streamed answer to its end or its failure. */
    public static Collected collect(AdapterResponse response) throws Exception {
        Buffer received = Buffer.buffer();
        AtomicBoolean failed = new AtomicBoolean();
        Promise<Void> done = Promise.promise();
        ((Body.Stream) response.body()).pipe().to(new Sink(received)).onComplete(ar -> {
            failed.set(ar.failed());
            done.complete();
        });
        await(done.future());
        return new Collected(received, failed.get());
    }

    private record Sink(Buffer received) implements WriteStream<Buffer> {

        @Override
        public WriteStream<Buffer> exceptionHandler(Handler<Throwable> handler) {
            return this;
        }

        @Override
        public Future<Void> write(Buffer data) {
            received.appendBuffer(data);
            return Future.succeededFuture();
        }

        @Override
        public void write(Buffer data, Handler<AsyncResult<Void>> handler) {
            received.appendBuffer(data);
            handler.handle(Future.succeededFuture());
        }

        @Override
        public void end(Handler<AsyncResult<Void>> handler) {
            handler.handle(Future.succeededFuture());
        }

        @Override
        public WriteStream<Buffer> setWriteQueueMaxSize(int maxSize) {
            return this;
        }

        @Override
        public boolean writeQueueFull() {
            return false;
        }

        @Override
        public WriteStream<Buffer> drainHandler(Handler<Void> handler) {
            return this;
        }
    }
}
