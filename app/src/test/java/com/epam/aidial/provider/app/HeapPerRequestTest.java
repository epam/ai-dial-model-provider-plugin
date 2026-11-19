package com.epam.aidial.provider.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerResponse;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** operations "Request body held once": the 96 MiB heap suffices only if a request body is not copied. */
class HeapPerRequestTest {

    private static final int SIZE = 2_000_000;

    private final HttpClient client =
            HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private final List<HttpServerResponse> held = new ArrayList<>();
    private Vertx vertx;
    private Process service;
    private Path log;
    private int port;

    @BeforeEach
    void start() throws Exception {
        vertx = Vertx.vertx();
        port = freePort();
        log = Files.createTempFile("heap-per-request", ".log");
        ProcessBuilder fork = new ProcessBuilder(
                        ProcessHandle.current().info().command().orElse("java"),
                        "-Xmx96m",
                        "-XX:+UseSerialGC",
                        "-XX:+ExitOnOutOfMemoryError",
                        "-cp",
                        System.getProperty("java.class.path"),
                        Main.class.getName())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile());
        fork.environment().keySet().removeIf(k -> k.startsWith("OTEL_") || k.equals("JAVA_TOOL_OPTIONS"));
        fork.environment()
                .putAll(Map.of("PORT", Integer.toString(port), "DIAL_URL", "http://127.0.0.1:9", "LOG_LEVEL", "ERROR"));
        service = fork.start();
        awaitHealth();
        // A failure, not a hang: whatever is still held ends, unanswered or cut short.
        vertx.setTimer(60_000, id -> held.stream().filter(r -> !r.ended()).forEach(HttpServerResponse::end));
    }

    @AfterEach
    void stop() throws Exception {
        service.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        try {
            Files.deleteIfExists(log);
        } catch (IOException e) { // Windows: the killed service may still hold it
            log.toFile().deleteOnExit();
        }
    }

    /** 20 bodies of 2 MB in flight: about 40 MB held once, about 120 MB held three times. */
    @Test
    void concurrentLargeBodiesInSmallHeap() throws Exception {
        int requests = 20;
        byte[] body = messagesBody(false);
        AtomicInteger unchanged = new AtomicInteger();
        // Answers nothing until every body has arrived, so that all of them are in flight in the service at once.
        int upstream = upstream((received, response) -> {
            if (Arrays.equals(received, body)) {
                unchanged.incrementAndGet();
            }
            held.add(response);
            if (held.size() == requests) {
                held.forEach(r ->
                        r.putHeader("Content-Type", "application/json").end("{\"id\":\"msg\",\"type\":\"message\"}"));
            }
        });
        List<CompletableFuture<HttpResponse<String>>> calls = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            calls.add(client.sendAsync(messages(upstream, body), HttpResponse.BodyHandlers.ofString()));
        }
        List<Integer> statuses = new ArrayList<>();
        for (CompletableFuture<HttpResponse<String>> call : calls) {
            statuses.add(status(call));
        }
        assertEquals(Collections.nCopies(requests, 200), statuses, output());
        assertEquals(requests, unchanged.get());
    }

    /** 60 bodies of 2 MB: the heap holds only if a body is released once its upstream request is sent. */
    @Test
    void bodiesReleasedWhileAnswersStream() throws Exception {
        int requests = 60;
        byte[] body = messagesBody(true);
        int upstream = upstream((received, response) -> {
            response.setChunked(true)
                    .putHeader("Content-Type", "text/event-stream")
                    .write("event: message_start\ndata: {}\n\n");
            held.add(response);
            if (held.size() == requests) {
                held.forEach(r -> r.end("event: message_stop\ndata: {}\n\n"));
            }
        });
        List<HttpResponse<InputStream>> streams = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            CompletableFuture<HttpResponse<InputStream>> call =
                    client.sendAsync(messages(upstream, body), HttpResponse.BodyHandlers.ofInputStream());
            try {
                // the headers, sent with the first event: this body has been sent upstream
                streams.add(call.get(60, TimeUnit.SECONDS));
            } catch (Exception e) {
                throw new AssertionError("request " + i + " failed: " + e + "\n" + output(), e);
            }
        }
        for (HttpResponse<InputStream> stream : streams) {
            assertEquals(200, stream.statusCode(), output());
            try (InputStream in = stream.body()) {
                String events = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(
                        events.startsWith("event: message_start")
                                && events.endsWith("event: message_stop\ndata: {}\n\n"),
                        events);
            }
        }
    }

    private static byte[] messagesBody(boolean stream) {
        String head = "{\"model\":\"m\",\"max_tokens\":1," + (stream ? "\"stream\":true," : "")
                + "\"messages\":[{\"role\":\"user\",\"content\":\"";
        String tail = "\"}]}";
        return (head + "x".repeat(SIZE - head.length() - tail.length()) + tail).getBytes(StandardCharsets.UTF_8);
    }

    private HttpRequest messages(int upstream, byte[] body) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/compat/openai/anthropic/v1/messages"))
                .timeout(Duration.ofSeconds(90))
                .header("Content-Type", "application/json")
                .header("X-Upstream-Key", "k")
                .header("X-Upstream-Endpoint", "http://127.0.0.1:" + upstream + "/v1/messages")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
    }

    private interface Upstream {
        void handle(byte[] received, HttpServerResponse response);
    }

    private int upstream(Upstream handler) throws Exception {
        HttpServer server = vertx.createHttpServer()
                .requestHandler(req -> req.body().onSuccess(b -> handler.handle(b.getBytes(), req.response())));
        return server.listen(0, "127.0.0.1")
                .toCompletionStage()
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS)
                .actualPort();
    }

    private static int status(CompletableFuture<HttpResponse<String>> call) {
        try {
            return call.get(120, TimeUnit.SECONDS).statusCode();
        } catch (Exception e) {
            return -1;
        }
    }

    private String output() {
        try {
            String output = Files.readString(log);
            return output.substring(Math.max(0, output.length() - 2000));
        } catch (IOException e) {
            return e.toString();
        }
    }

    private void awaitHealth() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline && service.isAlive()) {
            try {
                if (client.send(
                                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health"))
                                                .build(),
                                        HttpResponse.BodyHandlers.discarding())
                                .statusCode()
                        == 200) {
                    return;
                }
            } catch (IOException e) {
                // not listening yet
            }
            Thread.sleep(200);
        }
        throw new AssertionError("the forked service did not start\n" + output());
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
