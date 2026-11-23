package com.epam.aidial.provider;

import static com.epam.aidial.provider.testing.MockUpstream.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.epam.aidial.provider.anthropic.AnthropicRoutes;
import com.epam.aidial.provider.anthropic.Targets;
import com.epam.aidial.provider.core.CompatRouter;
import com.epam.aidial.provider.core.Config;
import com.epam.aidial.provider.core.HttpServerShim;
import com.epam.aidial.provider.core.ModelProviderVerticle;
import com.epam.aidial.provider.core.Routes;
import com.epam.aidial.provider.credentials.AwsCredentials;
import com.epam.aidial.provider.credentials.AzureAd;
import com.epam.aidial.provider.credentials.GoogleTokenSource;
import com.epam.aidial.provider.upstream.UpstreamClient;
import com.sun.management.OperatingSystemMXBean;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.RequestOptions;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

/**
 * Process CPU per request after a warm-up, JIT and GC included: the client, the service (through its HTTP shim) and
 * the upstream share one process and one event loop. Run on its own:
 * {@code ./gradlew :provider:test -Pbench --tests '*PassthroughBench'}; numbers vary by ±25%.
 */
@Tag("bench")
class PassthroughBench {

    private static final int WARMUP = 3000;
    private static final int REQUESTS = 1000;
    private static final int CONCURRENCY = 8;

    private static Vertx vertx;
    private static HttpClient client;
    private static int port;
    private static String upstream;

    @BeforeAll
    static void start() throws Exception {
        vertx = Vertx.vertx(new VertxOptions().setEventLoopPoolSize(1));
        HttpServer upstreamServer = await(vertx.createHttpServer()
                .requestHandler(r -> r.body().onSuccess(b -> {
                    if (r.path().startsWith("/model/")) {
                        r.response()
                                .putHeader("Content-Type", "application/vnd.amazon.eventstream")
                                .end(EVENT_STREAM);
                    } else {
                        r.response()
                                .putHeader("Content-Type", "text/event-stream")
                                .end(MESSAGES_SSE);
                    }
                }))
                .listen(0, "127.0.0.1"));
        String up = "http://127.0.0.1:" + upstreamServer.actualPort();
        upstream = up;

        Config config = Config.from(Map.of(
                "ANTHROPIC_VERTEX_BASE_URL",
                up,
                "AWS_DEFAULT_REGION",
                "us-east-1",
                "AWS_ACCESS_KEY_ID",
                "AKID",
                "AWS_SECRET_ACCESS_KEY",
                "SECRET",
                "ANTHROPIC_BEDROCK_BASE_URL",
                up,
                "HTTP_MAX_CONNECTIONS",
                "64",
                "ANTHROPIC_MAX_CONNECTIONS",
                "64"));
        AzureAd azure = new AzureAd((scope, aborted) -> Future.succeededFuture("aad"), "s");
        GoogleTokenSource google = aborted -> Future.succeededFuture("t");
        AwsCredentials aws = new AwsCredentials(
                vertx, config, () -> StaticCredentialsProvider.create(AwsBasicCredentials.create("AKID", "SECRET")));
        Routes vertexai = Routes.of(new AnthropicRoutes(
                "/compat/vertexai",
                Targets.vertexai(config),
                new UpstreamClient(vertx, config.proxies(), config.vertexai()),
                azure,
                google,
                Clock.systemUTC()));
        Routes bedrock = Routes.of(new AnthropicRoutes(
                "/compat/bedrock",
                Targets.bedrock(config, aws, null),
                new UpstreamClient(vertx, config.proxies(), config.bedrock()),
                azure,
                google,
                Clock.systemUTC()));
        Routes openai = Routes.of(new AnthropicRoutes(
                "/compat/openai",
                Targets.openai(),
                new UpstreamClient(vertx, config.proxies(), config.openai()),
                azure,
                google,
                Clock.systemUTC()));
        HttpServer server = await(vertx.createHttpServer(ModelProviderVerticle.serverOptions("127.0.0.1", 0))
                .requestHandler(new HttpServerShim(new CompatRouter(
                        Map.of("/compat/vertexai", vertexai, "/compat/bedrock", bedrock, "/compat/openai", openai))))
                .listen());
        port = server.actualPort();
        client = vertx.createHttpClient(new HttpClientOptions().setMaxPoolSize(CONCURRENCY));
    }

    @AfterAll
    static void stop() throws Exception {
        await(vertx.close());
    }

    private static final String MESSAGES_SSE =
            "event: content_block_delta\ndata: {\"type\":\"content_block_delta\"}\n\n".repeat(50);
    private static final Buffer EVENT_STREAM = eventStream();

    private static Buffer eventStream() {
        Buffer out = Buffer.buffer();
        out.appendBuffer(frame("""
                {"type":"message_start","message":{"id":"m"}}"""));
        for (int i = 0; i < 1000; i++) {
            out.appendBuffer(
                    frame(
                            """
                                    {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":" word"}}"""));
        }
        return out;
    }

    private static Buffer frame(String json) {
        return frame(
                "chunk",
                "{\"bytes\":\"" + Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8))
                        + "\",\"p\":\"abcd\"}");
    }

    private static Buffer frame(String type, String payload) {
        Buffer h = Buffer.buffer();
        for (String[] kv :
                new String[][] {{":event-type", type}, {":content-type", "application/json"}, {":message-type", "event"}
                }) {
            h.appendByte((byte) kv[0].length())
                    .appendString(kv[0])
                    .appendByte((byte) 7)
                    .appendUnsignedShort(kv[1].length())
                    .appendString(kv[1]);
        }
        byte[] p = payload.getBytes(StandardCharsets.UTF_8);
        Buffer m = Buffer.buffer().appendInt(12 + h.length() + p.length + 4).appendInt(h.length());
        m.appendUnsignedInt(crc(m)).appendBuffer(h).appendBytes(p);
        return m.appendUnsignedInt(crc(m));
    }

    private static long crc(Buffer b) {
        CRC32 crc = new CRC32();
        crc.update(b.getBytes());
        return crc.getValue();
    }

    private static String prompt(int words, String last) {
        String msg = "{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\""
                + "lorem ipsum dolor sit amet ".repeat(words) + "\"}]},";
        return msg.repeat(100) + last;
    }

    @Test
    void vertexMessagesStream() throws Exception {
        String body =
                "{\"model\":\"claude-x\",\"stream\":true,\"max_tokens\":1024,\"system\":[{\"type\":\"text\",\"text\":\"s\",\"cache_control\":{\"type\":\"ephemeral\"}}],"
                        + "\"messages\":["
                        + prompt(
                                160,
                                """
                                        {"role":"user","content":[{"type":"text","text":"q","cache_control":{"type":"ephemeral"}}]}]}""");
        run(
                "Anthropic Messages, Vertex AI stream",
                "/compat/vertexai/anthropic/v1/messages",
                body,
                MultiMap.caseInsensitiveMultiMap()
                        .add("X-Upstream-Extra-Data", "{\"region\":\"r\",\"project\":\"p\"}"));
    }

    @Test
    void bedrockInvokeStream() throws Exception {
        String body = "{\"model\":\"anthropic.claude-x\",\"stream\":true,\"max_tokens\":1024,\"messages\":["
                + prompt(
                        220,
                        """
                                {"role":"user","content":[{"type":"text","text":"q","cache_control":{"type":"ephemeral"}}]}]}""");
        run(
                "Anthropic Messages, Bedrock invoke stream (1,000 events)",
                "/compat/bedrock/anthropic/v1/messages",
                body,
                MultiMap.caseInsensitiveMultiMap());
    }

    /** The breakpoint is on the last message only. */
    @Test
    void openaiMessagesStream() throws Exception {
        String body = "{\"model\":\"claude-x\",\"stream\":true,\"max_tokens\":1024,\"messages\":["
                + prompt(
                        220,
                        """
                                {"role":"user","content":[{"type":"text","text":"q","cache_control":{"type":"ephemeral"}}]}]}""");
        run(
                "Anthropic Messages, openai adapter stream",
                "/compat/openai/anthropic/v1/messages",
                body,
                MultiMap.caseInsensitiveMultiMap()
                        .add("X-Upstream-Endpoint", upstream + "/v1/messages")
                        .add("X-Upstream-Key", "k"));
    }

    /** The baseline: the same request and answer, sent to the upstream directly. */
    @Test
    void baseline() throws Exception {
        String body = "{\"model\":\"gpt-5\",\"stream\":true,\"input\":["
                + prompt(220, "{\"role\":\"user\",\"content\":\"q\"}]}");
        int servicePort = port;
        port = Integer.parseInt(upstream.substring(upstream.lastIndexOf(':') + 1));
        try {
            run(
                    "baseline: client and upstream only, Messages-sized",
                    "/v1/messages",
                    body,
                    MultiMap.caseInsensitiveMultiMap());
        } finally {
            port = servicePort;
        }
    }

    private void run(String name, String path, String body, MultiMap headers) throws Exception {
        run(name, path, body, headers, WARMUP, REQUESTS);
    }

    private void run(String name, String path, String body, MultiMap headers, int warmup, int requests)
            throws Exception {
        Buffer payload = Buffer.buffer(body);
        long[] received = new long[1];
        batch(path, payload, headers, warmup, received);
        var os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        long cpu = os.getProcessCpuTime();
        long wall = System.nanoTime();
        received[0] = 0;
        batch(path, payload, headers, requests, received);
        double cpuMs = (os.getProcessCpuTime() - cpu) / 1e6 / requests;
        double wallMs = (System.nanoTime() - wall) / 1e6 / requests;
        System.out.printf(
                "BENCH %s: %.2f ms CPU/request, %.2f ms wall/request, %d KB in, %d KB out%n",
                name, cpuMs, wallMs, payload.length() / 1024, received[0] / requests / 1024);
    }

    private static void batch(String path, Buffer body, MultiMap headers, int n, long[] received) throws Exception {
        List<Future<Void>> lanes = new ArrayList<>();
        AtomicInteger next = new AtomicInteger();
        for (int i = 0; i < CONCURRENCY; i++) {
            lanes.add(lane(path, body, headers, n, next, received));
        }
        for (Future<Void> lane : lanes) {
            lane.toCompletionStage().toCompletableFuture().get(10, TimeUnit.MINUTES);
        }
    }

    private static Future<Void> lane(
            String path, Buffer body, MultiMap headers, int n, AtomicInteger next, long[] received) {
        if (next.getAndIncrement() >= n) {
            return Future.succeededFuture();
        }
        RequestOptions options = new RequestOptions()
                .setMethod(HttpMethod.POST)
                .setHost("127.0.0.1")
                .setPort(port)
                .setURI(path)
                .setHeaders(headers);
        return client.request(options)
                .compose(r -> r.send(body))
                .compose(response -> {
                    assertEquals(200, response.statusCode(), path);
                    return response.body();
                })
                .compose(b -> {
                    synchronized (received) {
                        received[0] += b.length();
                    }
                    return lane(path, body, headers, n, next, received);
                });
    }
}
