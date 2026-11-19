package com.epam.aidial.provider.upstream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.epam.aidial.provider.core.Config;
import com.epam.aidial.provider.testing.Await;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UpstreamClientTest {

    private static final Future<Void> NEVER = Promise.<Void>promise().future();

    private Vertx vertx;
    private int port;
    private final List<String> received = new CopyOnWriteArrayList<>();
    private final Map<String, Long> arrivedAt = new ConcurrentHashMap<>();
    private final Map<String, MultiMap> headersOf = new ConcurrentHashMap<>();
    private final AtomicInteger concurrent = new AtomicInteger();
    private final AtomicInteger maxConcurrent = new AtomicInteger();
    private final CompletableFuture<Void> stalledClosed = new CompletableFuture<>();

    @BeforeEach
    void start() throws Exception {
        vertx = Vertx.vertx();
        HttpServer server =
                await(vertx.createHttpServer().requestHandler(this::upstream).listen(0, "127.0.0.1"));
        port = server.actualPort();
    }

    @AfterEach
    void stop() throws Exception {
        await(vertx.close());
    }

    /** The mock upstream: the path says how to answer. */
    private void upstream(HttpServerRequest request) {
        String path = request.path();
        received.add(path);
        arrivedAt.put(path, System.nanoTime());
        headersOf.put(path, MultiMap.caseInsensitiveMultiMap().addAll(request.headers()));
        HttpServerResponse response = request.response();
        int now = concurrent.incrementAndGet();
        maxConcurrent.accumulateAndGet(now, Math::max);
        response.endHandler(v -> concurrent.decrementAndGet());
        response.closeHandler(v -> concurrent.decrementAndGet());
        switch (path) {
            case "/hold" -> {
                response.setChunked(true).write("first");
                vertx.setTimer(500, t -> response.end("last"));
            }
            case "/hold-long" -> {
                response.setChunked(true).write("first");
                vertx.setTimer(1500, t -> response.end("last"));
            }
            case "/stall" -> {
                request.connection().closeHandler(v -> stalledClosed.complete(null));
                response.setChunked(true).write("first");
            }
            case "/late" -> vertx.setTimer(800, t -> response.end("late"));
            case "/burst" -> {
                response.setChunked(true);
                for (int i = 0; i < 5; i++) {
                    response.write(Buffer.buffer(new byte[64 << 10]));
                }
                response.end();
            }
            case "/redirect" ->
                response.setStatusCode(307).putHeader("Location", "/elsewhere").end();
            default -> response.end("ok");
        }
    }

    private UpstreamClient client(int max, long waitMillis, long readTimeoutMillis, Map<String, String> settings) {
        // The wait as the tests write it: 0 without limit, below 0 not at all.
        Limits.PoolWait wait = waitMillis == 0
                ? new Limits.PoolWait.Unbounded()
                : waitMillis < 0
                        ? new Limits.PoolWait.FailFast()
                        : new Limits.PoolWait.Bounded(Duration.ofMillis(waitMillis));
        return new UpstreamClient(
                vertx, Config.from(settings).proxies(), Limits.capped(max, wait, Duration.ofMillis(readTimeoutMillis)));
    }

    private Future<UpstreamClient.UpstreamResponse> get(UpstreamClient client, String path, Future<Void> aborted) {
        return client.send(
                HttpMethod.GET, "http://127.0.0.1:" + port + path, MultiMap.caseInsensitiveMultiMap(), null, aborted);
    }

    private String read(Future<UpstreamClient.UpstreamResponse> response) throws Exception {
        return await(response.compose(UpstreamClient.UpstreamResponse::body)).toString();
    }

    @Test
    void callBeyondTheCapWaits() throws Exception {
        UpstreamClient client = client(1, 0, 60_000, Map.of());
        Future<String> first =
                get(client, "/hold", NEVER).compose(r -> r.body()).map(Buffer::toString);
        awaitReceived("/hold");
        Future<String> second = get(client, "/ok", NEVER).compose(r -> r.body()).map(Buffer::toString);

        assertEquals("firstlast", await(first));
        assertEquals("ok", await(second));
        long holdEnd = arrivedAt.get("/hold") + TimeUnit.MILLISECONDS.toNanos(500);
        assertTrue(
                arrivedAt.get("/ok") >= holdEnd - TimeUnit.MILLISECONDS.toNanos(50),
                "the second call was sent before the first body ended");
    }

    @Test
    void poolTimeout() throws Exception {
        UpstreamClient client = client(1, 300, 60_000, Map.of());
        Future<String> first =
                get(client, "/hold-long", NEVER).compose(r -> r.body()).map(Buffer::toString);
        awaitReceived("/hold-long");
        long start = System.nanoTime();
        Throwable failure = failure(get(client, "/ok", NEVER));

        assertInstanceOf(PoolExhaustedException.class, failure);
        long waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(waited >= 250 && waited < 1200, "waited " + waited + " ms");
        assertEquals("firstlast", await(first));
        assertFalse(received.contains("/ok"));
    }

    @Test
    void noWaitFailsAtOnce() throws Exception {
        UpstreamClient client = client(1, -1, 60_000, Map.of());
        Future<String> first =
                get(client, "/hold", NEVER).compose(r -> r.body()).map(Buffer::toString);
        awaitReceived("/hold");
        long start = System.nanoTime();
        assertInstanceOf(PoolExhaustedException.class, failure(get(client, "/ok", NEVER)));
        assertTrue(System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(200));
        await(first);
    }

    @Test
    void noWaitLimit() throws Exception {
        UpstreamClient client = client(1, 0, 60_000, Map.of());
        Future<String> first =
                get(client, "/hold-long", NEVER).compose(r -> r.body()).map(Buffer::toString);
        awaitReceived("/hold-long");
        assertEquals("ok", read(get(client, "/ok", NEVER)));
        await(first);
    }

    @Test
    void clientGoneWhileWaiting() throws Exception {
        UpstreamClient client = client(1, 0, 60_000, Map.of());
        Future<String> first =
                get(client, "/hold", NEVER).compose(r -> r.body()).map(Buffer::toString);
        awaitReceived("/hold");
        Promise<Void> aborted = Promise.promise();
        Future<UpstreamClient.UpstreamResponse> second = get(client, "/ok", aborted.future());
        vertx.setTimer(100, t -> aborted.complete());

        assertInstanceOf(ClientGoneException.class, failure(second));
        await(first);
        Thread.sleep(100); // nothing must happen: the abandoned request is never sent
        assertFalse(received.contains("/ok"));
        assertEquals("ok", read(get(client, "/ok", NEVER)), "the abandoned wait kept no slot");
    }

    @Test
    void noCap() throws Exception {
        UpstreamClient client = client(0, 0, 60_000, Map.of());
        List<Future<String>> calls = new CopyOnWriteArrayList<>();
        for (int i = 0; i < 20; i++) {
            calls.add(get(client, "/hold", NEVER).compose(r -> r.body()).map(Buffer::toString));
        }
        for (Future<String> call : calls) {
            assertEquals("firstlast", await(call));
        }
        assertEquals(20, maxConcurrent.get());
    }

    @Test
    void stalledBodyTimesOut() throws Exception {
        UpstreamClient client = client(1, -1, 300, Map.of());
        long start = System.nanoTime();
        Throwable failure = failure(get(client, "/stall", NEVER).compose(UpstreamClient.UpstreamResponse::body));

        assertInstanceOf(UpstreamTimeoutException.class, failure);
        assertTrue(System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(280));
        stalledClosed.get(5, TimeUnit.SECONDS);
        assertEquals("ok", read(get(client, "/ok", NEVER)), "the timed-out call kept no slot");
    }

    @Test
    void headersTooLate() throws Exception {
        UpstreamClient client = client(1, -1, 300, Map.of());
        assertInstanceOf(UpstreamTimeoutException.class, failure(get(client, "/late", NEVER)));
        assertEquals("ok", read(get(client, "/ok", NEVER)), "the timed-out call kept no slot");
    }

    @Test
    void slowConsumerDoesNotTimeOut() throws Exception {
        UpstreamClient client = client(1, -1, 300, Map.of());
        UpstreamClient.UpstreamResponse response = await(get(client, "/burst", NEVER));
        CompletableFuture<Integer> total = new CompletableFuture<>();
        AtomicInteger bytes = new AtomicInteger();
        var stream = response.stream();
        stream.handler(chunk -> {
                    bytes.addAndGet(chunk.length());
                    stream.pause();
                    vertx.setTimer(400, t -> stream.resume());
                })
                .endHandler(v -> total.complete(bytes.get()))
                .exceptionHandler(total::completeExceptionally);
        stream.resume();

        assertEquals(5 * (64 << 10), total.get(20, TimeUnit.SECONDS));
    }

    @Test
    void slotNeverLeaks() throws Exception {
        UpstreamClient client = client(1, -1, 60_000, Map.of());
        Throwable refused = failure(
                client.send(HttpMethod.GET, "http://127.0.0.1:1/x", MultiMap.caseInsensitiveMultiMap(), null, NEVER));
        assertFalse(refused instanceof PoolExhaustedException);
        assertEquals("ok", read(get(client, "/ok", NEVER)), "after a connection failure");

        await(get(client, "/hold", NEVER)).discard();
        assertEquals("ok", read(get(client, "/ok", NEVER)), "after a discarded body");

        // A secret with a trailing newline, a common mistake in a mounted secret: refused without its value.
        Throwable badHeader = failure(client.send(
                HttpMethod.GET,
                "http://127.0.0.1:" + port + "/ok",
                MultiMap.caseInsensitiveMultiMap().add("Authorization", "Bearer secret-token\n"),
                null,
                NEVER));
        assertInstanceOf(IllegalArgumentException.class, badHeader);
        assertFalse(badHeader.getMessage().contains("secret-token"), badHeader.getMessage());
        assertEquals("ok", read(get(client, "/ok", NEVER)), "after a header HTTP does not allow");

        Promise<Void> aborted = Promise.promise();
        UpstreamClient.UpstreamResponse held = await(get(client, "/hold-long", aborted.future()));
        CompletableFuture<Throwable> broken = new CompletableFuture<>();
        held.stream().handler(b -> {}).exceptionHandler(broken::complete).endHandler(v -> broken.complete(null));
        held.stream().resume();
        aborted.complete();
        assertTrue(broken.get(5, TimeUnit.SECONDS) != null, "the aborted stream failed");
        assertEquals("ok", read(get(client, "/ok", NEVER)), "after the client went");

        assertEquals("ok", read(get(client, "/ok", NEVER)), "after a body read to its end");
    }

    @Test
    void requestHeaders() throws Exception {
        UpstreamClient client = client(1, -1, 60_000, Map.of());
        read(client.send(
                HttpMethod.POST,
                "http://127.0.0.1:" + port + "/default",
                MultiMap.caseInsensitiveMultiMap(),
                null,
                NEVER));
        MultiMap sent = headersOf.get("/default");
        assertEquals(UpstreamClient.USER_AGENT, sent.get("User-Agent"));
        assertEquals("0", sent.get("Content-Length"));
        assertNull(sent.get("Accept-Encoding"));

        read(client.send(
                HttpMethod.POST,
                "http://127.0.0.1:" + port + "/own",
                MultiMap.caseInsensitiveMultiMap()
                        .add("User-Agent", "sdk/1")
                        .add("X-A", "1")
                        .add("X-A", "2"),
                Buffer.buffer("{}"),
                NEVER));
        assertEquals("sdk/1", headersOf.get("/own").get("User-Agent"));
        assertEquals(List.of("1", "2"), headersOf.get("/own").getAll("X-A"));
        assertEquals("2", headersOf.get("/own").get("Content-Length"));
    }

    @Test
    void redirectNotFollowed() throws Exception {
        UpstreamClient client = client(1, -1, 60_000, Map.of());
        UpstreamClient.UpstreamResponse response = await(get(client, "/redirect", NEVER));
        assertEquals(307, response.status());
        await(response.body());
        assertFalse(received.contains("/elsewhere"));
    }

    @Test
    void proxyFromTheEnvironment() throws Exception {
        List<String> proxied = new CopyOnWriteArrayList<>();
        HttpServer proxy = await(vertx.createHttpServer()
                .requestHandler(request -> {
                    proxied.add(request.uri());
                    request.response().end("via proxy");
                })
                .listen(0, "127.0.0.1"));
        String proxyUrl = "http://127.0.0.1:" + proxy.actualPort();

        UpstreamClient viaProxy = client(1, -1, 60_000, Map.of("HTTP_PROXY", proxyUrl));
        assertEquals(
                "via proxy",
                read(viaProxy.send(
                        HttpMethod.GET, "http://upstream.test/x", MultiMap.caseInsensitiveMultiMap(), null, NEVER)));
        assertEquals(List.of("http://upstream.test/x"), proxied);
        assertEquals("ok", read(get(viaProxy, "/ok", NEVER)), "loopback is always direct");

        UpstreamClient excluded =
                client(1, -1, 60_000, Map.of("http_proxy", proxyUrl, "NO_PROXY", "other.test, upstream.test"));
        failure(excluded.send(
                HttpMethod.GET, "http://upstream.test/x", MultiMap.caseInsensitiveMultiMap(), null, NEVER));
        UpstreamClient subdomainsOnly =
                client(1, -1, 60_000, Map.of("HTTP_PROXY", proxyUrl, "NO_PROXY", ".upstream.test"));
        assertEquals(
                "via proxy",
                read(subdomainsOnly.send(
                        HttpMethod.GET, "http://upstream.test/y", MultiMap.caseInsensitiveMultiMap(), null, NEVER)));
        assertEquals(List.of("http://upstream.test/x", "http://upstream.test/y"), proxied);
    }

    private void awaitReceived(String path) throws InterruptedException {
        Await.until(() -> received.contains(path), Duration.ofSeconds(5));
        assertTrue(received.contains(path), path + " never reached the upstream");
    }

    private static Throwable failure(Future<?> future) {
        ExecutionException e = assertThrows(
                ExecutionException.class,
                () -> future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS));
        return e.getCause();
    }

    static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
