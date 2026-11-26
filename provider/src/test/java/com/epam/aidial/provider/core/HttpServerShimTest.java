package com.epam.aidial.provider.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.epam.aidial.provider.api.AdapterRequest;
import com.epam.aidial.provider.api.AdapterResponse;
import com.epam.aidial.provider.api.Body;
import com.epam.aidial.provider.api.Handler;
import com.epam.aidial.provider.testing.Await;
import com.epam.aidial.provider.testing.Requests;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpServerShimTest {

    private final java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
            .version(java.net.http.HttpClient.Version.HTTP_1_1)
            .build();
    private Vertx vertx;
    private HttpClient upstreamClient;
    private HttpServerShim shim;
    private int port;
    private volatile Handler provider;

    @BeforeEach
    void start() throws Exception {
        vertx = Vertx.vertx();
        upstreamClient = vertx.createHttpClient();
        shim = new HttpServerShim(request -> provider.handle(request), Requests.NO_CORE);
        port = freePort();
        await(vertx.deployVerticle(
                new ModelProviderVerticle(shim, ModelProviderVerticle.serverOptions("127.0.0.1", port))));
    }

    @AfterEach
    void stop() throws Exception {
        await(vertx.close());
    }

    @Test
    void requestReachesTheProviderWhole() throws Exception {
        byte[] sent = new byte[1 << 20];
        Arrays.fill(sent, (byte) 'a');
        AtomicReference<AdapterRequest> received = new AtomicReference<>();
        provider = request -> {
            received.set(request);
            return Future.succeededFuture(AdapterResponse.json(200, "{}"));
        };
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(uri("/compat/openai/x?api-version=1&a=%20"))
                        .header("X-Upstream-Key", "secret")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(sent))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        AdapterRequest request = received.get();
        assertEquals(HttpMethod.POST, request.method());
        assertEquals("/compat/openai/x", request.path());
        assertEquals("api-version=1&a=%20", request.query());
        assertEquals("secret", request.headers().get("x-upstream-key"));
        assertArrayEquals(sent, request.body().getBytes());
        // A declared length: one exact array, which routes read without a copy.
        assertSame(Buffers.array(request.body()), Buffers.array(request.body()));
        assertFalse(request.aborted().isComplete());
    }

    @Test
    void bytesResponseGetsTheServersFraming() throws Exception {
        provider = request -> {
            MultiMap headers = MultiMap.caseInsensitiveMultiMap()
                    .add("X-Kept", "1")
                    .add("X-Kept", "2")
                    .set("Content-Type", "application/json")
                    .set("Content-Length", "999")
                    .set("Transfer-Encoding", "chunked")
                    .set("Connection", "close")
                    .set("Server", "upstream")
                    .set("Date", "yesterday");
            return Future.succeededFuture(
                    new AdapterResponse(201, headers, new Body.Bytes(Buffer.buffer("{\"a\":1}"))));
        };
        HttpResponse<String> response =
                client.send(HttpRequest.newBuilder(uri("/")).build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(201, response.statusCode());
        assertEquals("{\"a\":1}", response.body());
        assertEquals(List.of("1", "2"), response.headers().allValues("x-kept"));
        assertEquals(
                "application/json",
                response.headers().firstValue("content-type").orElseThrow());
        assertEquals("7", response.headers().firstValue("content-length").orElseThrow());
        assertTrue(response.headers().firstValue("transfer-encoding").isEmpty());
        assertTrue(response.headers().firstValue("connection").isEmpty());
        assertTrue(response.headers().firstValue("server").isEmpty());
        assertTrue(response.headers().firstValue("date").isEmpty());
    }

    @Test
    void streamIsRelayedAsItArrives() throws Exception {
        int upstream = upstream(request -> {
            HttpServerResponse response =
                    request.response().setChunked(true).putHeader("Content-Type", "text/event-stream");
            response.write("data: 1\n\n");
            vertx.setTimer(2000, t -> response.end("data: [DONE]\n\n"));
        });
        provider = relay(upstream);
        long start = System.nanoTime();
        String head = exchange("GET / HTTP/1.1\r\nHost: x\r\n\r\n", "data: 1\n\n");

        assertTrue(System.nanoTime() - start < 1_500_000_000L, "the first event waited for the end of the stream");
        assertTrue(head.startsWith("HTTP/1.1 200 OK\r\n"), head);
        assertTrue(head.toLowerCase().contains("content-type: text/event-stream\r\n"), head);
        assertTrue(head.toLowerCase().contains("transfer-encoding: chunked\r\n"), head);
    }

    /** operations "Request body held once": not while the answer streams. */
    @Test
    void bodyIsReleasedWhileTheAnswerStreams() throws Exception {
        int upstream = upstream(request -> {
            HttpServerResponse response = request.response().setChunked(true);
            response.write("data: 1\n\n");
            vertx.setTimer(10_000, t -> response.end());
        });
        Handler relay = relay(upstream);
        AtomicReference<WeakReference<byte[]>> body = new AtomicReference<>();
        provider = request -> {
            body.set(new WeakReference<>(Buffers.array(request.body())));
            return relay.handle(request);
        };
        byte[] sent = new byte[1 << 20];
        Arrays.fill(sent, (byte) 'a');
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            out.write(("POST / HTTP/1.1\r\nHost: x\r\nContent-Length: " + sent.length + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.write(sent);
            out.flush();
            readUntil(socket.getInputStream(), "data: 1\n\n");
            Await.until(
                    () -> {
                        System.gc();
                        return body.get().get() == null;
                    },
                    Duration.ofSeconds(5));
            assertNull(body.get().get(), "the body outlived its handover while the answer streams");
        }
    }

    @Test
    void streamHeadersAreSentAtOnce() throws Exception {
        int upstream = upstream(request -> {
            HttpServerResponse response = request.response().setChunked(true);
            response.writeHead();
            vertx.setTimer(1500, t -> response.end("data: late\n\n"));
        });
        provider = relay(upstream);
        long start = System.nanoTime();
        String head = exchange("GET / HTTP/1.1\r\nHost: x\r\n\r\n", "\r\n\r\n");

        assertTrue(head.startsWith("HTTP/1.1 200 OK\r\n"), head);
        assertTrue(System.nanoTime() - start < 1_000_000_000L, "the headers waited for the first chunk");
    }

    @Test
    void brokenStreamIsTruncated() throws Exception {
        int upstream = upstream(request -> {
            request.response().setChunked(true).write("data: 1\n\n");
            vertx.setTimer(50, t -> request.connection().close());
        });
        provider = relay(upstream);
        String raw = exchange("GET / HTTP/1.1\r\nHost: x\r\n\r\n", null);

        assertTrue(raw.startsWith("HTTP/1.1 200 OK\r\n"), raw);
        assertTrue(raw.contains("data: 1\n\n"), raw);
        assertFalse(raw.endsWith("0\r\n\r\n"), "a broken stream must not end cleanly: " + raw);
        awaitInFlight(0);
    }

    @Test
    void streamBrokenBeforeItIsHandedOverIsTruncated() throws Exception {
        int upstream = upstream(request -> {
            request.response().setChunked(true).write("data: 1\n\n");
            vertx.setTimer(20, t -> request.connection().close());
        });
        // The provider has the stream at once but answers 300 ms later, as one crossing executeBlocking would.
        provider = request -> upstreamClient
                .request(HttpMethod.GET, upstream, "127.0.0.1", "/")
                .compose(upstreamRequest -> upstreamRequest.send())
                .compose(response -> {
                    AdapterResponse answer = new AdapterResponse(
                            200, MultiMap.caseInsensitiveMultiMap(), new Body.Stream(response.pipe()));
                    Promise<AdapterResponse> late = Promise.promise();
                    vertx.setTimer(300, t -> late.complete(answer));
                    return late.future();
                });
        String raw = exchange("GET / HTTP/1.1\r\nHost: x\r\n\r\n", null);

        assertTrue(raw.startsWith("HTTP/1.1 200 OK\r\n"), raw);
        assertFalse(raw.endsWith("0\r\n\r\n"), "a broken stream must not end cleanly: " + raw);
        awaitInFlight(0);
    }

    @Test
    void clientGoneAbortsTheUpstreamCall() throws Exception {
        CompletableFuture<Void> upstreamClosed = new CompletableFuture<>();
        int upstream = upstream(request -> {
            request.connection().closeHandler(v -> upstreamClosed.complete(null));
            request.response().setChunked(true).write("data: 1\n\n");
        });
        AtomicReference<Future<Void>> aborted = new AtomicReference<>();
        Handler relay = relay(upstream);
        provider = request -> {
            aborted.set(request.aborted());
            return relay.handle(request);
        };
        exchange("GET / HTTP/1.1\r\nHost: x\r\n\r\n", "data: 1\n\n");

        upstreamClosed.get(5, TimeUnit.SECONDS);
        assertTrue(aborted.get().succeeded());
        awaitInFlight(0);
    }

    @Test
    void clientGoneUnderBackpressureAbortsTheUpstreamCall() throws Exception {
        CompletableFuture<Void> upstreamClosed = new CompletableFuture<>();
        Buffer chunk = Buffer.buffer(new byte[64 << 10]);
        AtomicBoolean backedUp = new AtomicBoolean();
        int upstream = upstream(request -> {
            request.connection().closeHandler(v -> upstreamClosed.complete(null));
            HttpServerResponse response = request.response().setChunked(true);
            vertx.setPeriodic(1, id -> {
                if (response.closed()) {
                    vertx.cancelTimer(id);
                } else if (response.writeQueueFull()) {
                    backedUp.set(true);
                } else {
                    response.write(chunk);
                }
            });
        });
        AtomicReference<Future<Void>> aborted = new AtomicReference<>();
        Handler relay = relay(upstream);
        provider = request -> {
            aborted.set(request.aborted());
            return relay.handle(request);
        };
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setReceiveBufferSize(4096);
            socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            // Never read: the writes queue up until the upstream's own queue is full.
            assertTrue(Await.until(backedUp::get, Duration.ofSeconds(10)), "the backpressure reached the upstream");
            socket.setSoLinger(true, 0);
        }

        upstreamClosed.get(5, TimeUnit.SECONDS);
        assertTrue(aborted.get().succeeded());
        awaitInFlight(0);
    }

    @Test
    void cutBodyNeverReachesTheProvider() throws Exception {
        AtomicReference<AdapterRequest> received = new AtomicReference<>();
        provider = request -> {
            received.set(request);
            return Future.succeededFuture(AdapterResponse.json(200, "{}"));
        };
        exchange("POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 100\r\n\r\n0123456789", "");

        awaitInFlight(0);
        assertNull(received.get());
    }

    @Test
    void malformedBodyNeverReachesTheProvider() throws Exception {
        AtomicReference<AdapterRequest> received = new AtomicReference<>();
        provider = request -> {
            received.set(request);
            return Future.succeededFuture(AdapterResponse.json(200, "{}"));
        };
        String raw =
                exchange("POST / HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\nzz\r\nab\r\n0\r\n\r\n", null);

        assertFalse(raw.contains(" 200 "), raw);
        awaitInFlight(0);
        assertNull(received.get());
    }

    @Test
    void failedProviderIsPlain500() throws Exception {
        Handler failedFuture = request -> Future.failedFuture(new IllegalStateException("bug"));
        Handler throwing = request -> {
            throw new IllegalStateException("bug");
        };
        Handler unwritable =
                request -> Future.succeededFuture(new AdapterResponse(200, MultiMap.caseInsensitiveMultiMap(), null));
        for (Handler failing : new Handler[] {failedFuture, throwing, unwritable}) {
            provider = failing;
            HttpResponse<String> response =
                    client.send(HttpRequest.newBuilder(uri("/")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(500, response.statusCode());
            assertEquals(
                    "text/plain; charset=utf-8",
                    response.headers().firstValue("content-type").orElseThrow());
            assertEquals("Internal Server Error", response.body());
        }
        awaitInFlight(0);
    }

    @Test
    void continueIsAnswered() throws Exception {
        provider = request ->
                Future.succeededFuture(AdapterResponse.json(200, request.body().toString()));
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            out.write("POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 5\r\nExpect: 100-continue\r\n\r\n"
                    .getBytes(StandardCharsets.UTF_8));
            String interim = readUntil(socket.getInputStream(), "\r\n\r\n");
            assertTrue(interim.startsWith("HTTP/1.1 100 Continue\r\n"), interim);
            out.write("hello".getBytes(StandardCharsets.UTF_8));
            assertTrue(readUntil(socket.getInputStream(), "hello").contains(" 200 OK"));
        }
    }

    @Test
    void drainClosesEachConnectionAfterItsResponse() throws Exception {
        provider = request -> Future.succeededFuture(AdapterResponse.json(200, "{}"));
        shim.drain();
        String raw = exchange("GET / HTTP/1.1\r\nHost: x\r\n\r\n", null); // to the end of the connection

        assertTrue(raw.startsWith("HTTP/1.1 200 OK\r\n"), raw);
        assertTrue(raw.toLowerCase().contains("connection: close\r\n"), raw);
        assertTrue(raw.endsWith("{}"), raw);
    }

    private Handler relay(int upstreamPort) {
        return request -> upstreamClient
                .request(HttpMethod.GET, upstreamPort, "127.0.0.1", "/")
                .compose(upstreamRequest -> {
                    request.aborted().onComplete(v -> upstreamRequest.reset());
                    return upstreamRequest.send();
                })
                .map(response -> new AdapterResponse(
                        response.statusCode(), response.headers(), new Body.Stream(response.pipe())));
    }

    private int upstream(io.vertx.core.Handler<HttpServerRequest> handler) throws Exception {
        HttpServer server =
                await(vertx.createHttpServer().requestHandler(handler).listen(0, "127.0.0.1"));
        return server.actualPort();
    }

    /**
     * Sends raw bytes and reads the answer: to the end of the connection, or, with until, up to that text, then closes
     * the connection; with an empty until, closes right after sending.
     */
    private String exchange(String request, String until) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            if ("".equals(until)) {
                return "";
            }
            return readUntil(socket.getInputStream(), until);
        }
    }

    private static String readUntil(InputStream in, String until) throws IOException {
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int n; (n = in.read(buffer)) > 0; ) {
            received.write(buffer, 0, n);
            if (until != null && received.toString(StandardCharsets.UTF_8).contains(until)) {
                break;
            }
        }
        return received.toString(StandardCharsets.UTF_8);
    }

    private void awaitInFlight(int expected) throws InterruptedException {
        Await.until(() -> shim.inFlight() == expected, Duration.ofSeconds(5));
        assertEquals(expected, shim.inFlight());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
