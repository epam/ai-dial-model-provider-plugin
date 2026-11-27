package com.epam.aidial.provider.core;

import static com.epam.aidial.provider.testing.MockUpstream.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.epam.aidial.provider.api.AdapterRequest;
import com.epam.aidial.provider.api.AdapterResponse;
import com.epam.aidial.provider.api.Body;
import com.epam.aidial.provider.api.Handler;
import com.epam.aidial.provider.testing.MockUpstream;
import com.epam.aidial.provider.translation.DialCoreHttp;
import com.epam.aidial.provider.upstream.ClientGoneException;
import com.epam.aidial.provider.upstream.Limits;
import com.epam.aidial.provider.upstream.UpstreamClient;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.streams.ReadStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The GET of DIAL Core through the core of a request: its retries, and what it asks of the core. */
class DialApiTest {

    private static final Future<Void> NEVER = Promise.<Void>promise().future();

    private Vertx vertx;
    private DialApi api;
    private MockUpstream dial;
    private final AtomicInteger calls = new AtomicInteger();
    private volatile int resets;

    @BeforeEach
    void start() throws Exception {
        vertx = Vertx.vertx();
        api = new DialApi(vertx);
        dial = new MockUpstream(vertx).on("/", r -> {
            if (calls.incrementAndGet() <= resets) {
                r.connection().close();
            } else {
                r.response().end("{\"roles\":[]}");
            }
        });
    }

    @AfterEach
    void stop() throws Exception {
        await(vertx.close());
    }

    /** What a service's requests have: DIAL Core over HTTP at DIAL_URL. */
    private Handler http(String dialUrl) {
        Config config = Config.from(Map.of("DIAL_URL", dialUrl));
        return new DialCoreHttp(
                new UpstreamClient(vertx, config.proxies(), Limits.uncapped(Duration.ofSeconds(30))), config.dialUrl());
    }

    @Test
    void userInfoUrlAndHeaders() throws Exception {
        for (Map.Entry<String, String> c : Map.of(
                        "",
                        "/v1/user/info",
                        "/",
                        "/v1/user/info",
                        "//",
                        "//v1/user/info",
                        "/base",
                        "/base/v1/user/info")
                .entrySet()) {
            Handler core = http(dial.url() + c.getKey());
            assertEquals(
                    "{\"roles\":[]}",
                    await(api.get(core, DialApi.USER_INFO, "k1", DialApi.Retry.TRANSPORT, NEVER))
                            .toString());
            MockUpstream.Seen seen = dial.last();
            assertEquals(c.getValue(), seen.path(), c.getKey());
            assertEquals("GET", seen.method());
            assertEquals("k1", seen.headers().get("Api-Key"));
        }
    }

    @Test
    void asksTheCoreWithTheKeyAndNoBody() throws Exception {
        List<AdapterRequest> asked = new ArrayList<>();
        Handler core = request -> {
            asked.add(request);
            return Future.succeededFuture(AdapterResponse.json(200, "{\"roles\":[\"r\"]}"));
        };
        assertEquals(
                "{\"roles\":[\"r\"]}",
                await(api.get(core, DialApi.USER_INFO, "k1", DialApi.Retry.TRANSPORT, NEVER))
                        .toString());
        assertEquals(1, asked.size());
        AdapterRequest request = asked.get(0);
        assertEquals(HttpMethod.GET, request.method());
        assertEquals("/v1/user/info", request.path());
        assertEquals(null, request.query());
        assertEquals("k1", request.headers().get("Api-Key"));
        assertEquals(0, request.body().length());
        assertEquals(core, request.core());
    }

    @Test
    void streamedBodyIsReadWhole() throws Exception {
        Handler core = request -> Future.succeededFuture(new AdapterResponse(
                200,
                MultiMap.caseInsensitiveMultiMap(),
                new Body.Stream(new Chunks(List.of("{\"ro", "les\":[]}")).pipe())));
        assertEquals(
                "{\"roles\":[]}",
                await(api.get(core, DialApi.USER_INFO, "k1", DialApi.Retry.TRANSPORT, NEVER))
                        .toString());
    }

    @Test
    void transportFailuresRetriedThreeTimes() throws Exception {
        Handler core = http(dial.url());
        resets = 2;
        assertEquals(
                "{\"roles\":[]}",
                await(api.get(core, DialApi.USER_INFO, "k1", DialApi.Retry.TRANSPORT, NEVER))
                        .toString());
        assertEquals(3, calls.get());

        calls.set(0);
        resets = 3;
        DialApi.DownloadException e = assertInstanceOf(
                DialApi.DownloadException.class,
                failure(api.get(core, DialApi.USER_INFO, "k1", DialApi.Retry.TRANSPORT, NEVER)));
        assertNotNull(e.getCause(), "the transport failure that ended the attempts");
        assertEquals(3, calls.get());
    }

    @Test
    void failureOfTheCoreCountsAsTransportFailure() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        Handler core = request -> {
            attempts.incrementAndGet();
            return Future.failedFuture(new IllegalStateException("the host is closed"));
        };
        DialApi.DownloadException e = assertInstanceOf(
                DialApi.DownloadException.class,
                failure(api.get(core, DialApi.USER_INFO, "k1", DialApi.Retry.TRANSPORT, NEVER)));
        assertEquals("the host is closed", e.getMessage());
        assertEquals(3, attempts.get());
    }

    @Test
    void coreThatThrowsFails() throws Exception {
        Handler core = request -> {
            throw new IllegalStateException("bug");
        };
        assertEquals(
                "bug",
                failure(api.get(core, DialApi.USER_INFO, "k1", DialApi.Retry.TRANSPORT, NEVER))
                        .getMessage());
    }

    @Test
    void statusesRetriedOnlyWhenAsked() throws Exception {
        Handler core = http(dial.url());
        for (int status : List.of(500, 429, 408, 302)) {
            dial.reply("/v1/", status, Map.of("Location", "/elsewhere"), "");
            int before = dial.seen().size();
            assertEquals(
                    "DIAL answered " + status,
                    failure(api.get(core, DialApi.USER_INFO, "k1", DialApi.Retry.TRANSPORT, NEVER))
                            .getMessage());
            assertEquals(before + 1, dial.seen().size(), status + " is not retried; a redirect is not followed");
        }
        dial.reply("/v1/", 429, Map.of(), "");
        int before = dial.seen().size();
        assertEquals(
                "DIAL answered 429",
                failure(api.get(core, DialApi.USER_INFO, "k1", DialApi.Retry.TRANSPORT_AND_STATUS, NEVER))
                        .getMessage());
        assertEquals(before + 3, dial.seen().size());
    }

    @Test
    void clientGoneStopsTheRetries() throws Exception {
        Handler core = http(dial.url());
        Promise<Void> aborted = Promise.promise();
        resets = 3;
        dial.on("/", r -> {
            calls.incrementAndGet();
            aborted.tryComplete();
            r.connection().close();
        });
        assertInstanceOf(
                ClientGoneException.class,
                failure(api.get(core, DialApi.USER_INFO, "k1", DialApi.Retry.TRANSPORT, aborted.future())));
        assertEquals(1, calls.get());
    }

    private static Throwable failure(Future<?> future) {
        return assertThrows(ExecutionException.class, () -> await(future)).getCause();
    }

    /** A stream of the given chunks, which flows once something is attached. */
    private static final class Chunks implements ReadStream<Buffer> {

        private final List<String> chunks;
        private io.vertx.core.Handler<Buffer> data;
        private io.vertx.core.Handler<Void> end;
        private boolean started;

        Chunks(List<String> chunks) {
            this.chunks = chunks;
        }

        private void flow() {
            if (started || data == null || end == null) {
                return;
            }
            started = true;
            chunks.forEach(c -> data.handle(Buffer.buffer(c)));
            end.handle(null);
        }

        @Override
        public Chunks exceptionHandler(io.vertx.core.Handler<Throwable> handler) {
            return this;
        }

        @Override
        public Chunks handler(io.vertx.core.Handler<Buffer> handler) {
            data = handler;
            flow();
            return this;
        }

        @Override
        public Chunks pause() {
            return this;
        }

        @Override
        public Chunks resume() {
            return this;
        }

        @Override
        public Chunks fetch(long amount) {
            return this;
        }

        @Override
        public Chunks endHandler(io.vertx.core.Handler<Void> handler) {
            end = handler;
            flow();
            return this;
        }
    }
}
