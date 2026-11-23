package com.epam.aidial.provider.openai;

import static com.epam.aidial.provider.testing.MockUpstream.await;
import static com.epam.aidial.provider.testing.RoutesFixture.read;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.epam.aidial.provider.api.AdapterRequest;
import com.epam.aidial.provider.api.AdapterResponse;
import com.epam.aidial.provider.core.Buffers;
import com.epam.aidial.provider.core.Config;
import com.epam.aidial.provider.core.Routes;
import com.epam.aidial.provider.credentials.AwsCredentials;
import com.epam.aidial.provider.credentials.AzureAd;
import com.epam.aidial.provider.credentials.BedrockToken;
import com.epam.aidial.provider.credentials.Secret;
import com.epam.aidial.provider.credentials.SigV4;
import com.epam.aidial.provider.testing.Await;
import com.epam.aidial.provider.testing.RoutesFixture;
import com.epam.aidial.provider.upstream.UpstreamClient;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerResponse;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

/** openai-responses: what the declarative cases of http-tests/cases/openai-responses cannot show. */
class ResponsesRoutesTest {

    private static final String HI = "{\"model\":\"gpt-5\",\"input\":\"hi\"}";

    @RegisterExtension
    final RoutesFixture fx = new RoutesFixture();

    private Routes routes(String... settings) {
        return Routes.of(responsesRoutes(settings));
    }

    private ResponsesRoutes responsesRoutes(String... settings) {
        Map<String, String> s = new HashMap<>(Map.of("HTTP_MAX_CONNECTIONS", "10"));
        for (int i = 0; i < settings.length; i += 2) {
            s.put(settings[i], settings[i + 1]);
        }
        Config config = Config.from(s);
        AwsCredentials aws = new AwsCredentials(
                fx.vertx(),
                config,
                () -> StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIDCHAIN", "s")));
        return new ResponsesRoutes(
                fx.vertx(),
                config,
                "/compat/openai",
                Targets.openai(config),
                new UpstreamClient(fx.vertx(), config.proxies(), config.openai()),
                new AzureAd((scope, aborted) -> Future.succeededFuture("aad-tok"), "scope"),
                aws,
                null,
                RoutesFixture.CLOCK);
    }

    private String v1() {
        return fx.mock().url() + "/v1/responses";
    }

    @Test
    void createServesTheRequestOfAnotherPathAsTheResponsesRouteDoes() throws Exception {
        fx.mock().reply("/v1/responses", 200, Map.of("Content-Type", "application/json"), "{\"id\":\"resp_1\"}");
        AdapterResponse answer = await(responsesRoutes()
                .create(new AdapterRequest(
                        HttpMethod.POST,
                        "/anthropic/v1/messages",
                        null,
                        MultiMap.caseInsensitiveMultiMap()
                                .add("X-Upstream-Endpoint", v1())
                                .add("X-Upstream-Key", "k"),
                        Buffer.buffer(HI),
                        Promise.<Void>promise().future())));
        assertEquals(200, answer.status());
        assertEquals("{\"id\":\"resp_1\"}", read(answer));
        assertEquals(HI, fx.mock().last().body());
    }

    /** operations "Request body held once": not while the upstream has yet to answer. */
    @Test
    void bomOfTheBodyIsSkipped() {
        byte[] received = ("\uFEFF" + HI).getBytes(StandardCharsets.UTF_8);
        ClientBody parsed = ClientBody.parse(received, Endpoint.CREATE);
        assertEquals(HI, new String(parsed.bytes(), StandardCharsets.UTF_8), "forwarded without the BOM");
        assertEquals("gpt-5", parsed.model());
    }

    @Test
    void bodyReleasedBeforeTheAnswer() throws Exception {
        List<HttpServerResponse> held = new CopyOnWriteArrayList<>();
        fx.mock().on("/hold/", r -> held.add(r.response().putHeader("Content-Type", "text/event-stream")));
        Held sent =
                sendHeld(routes(), "{\"model\":\"gpt-5\",\"stream\":true,\"input\":\"" + "x".repeat(100_000) + "\"}");
        Await.until(() -> !held.isEmpty(), Duration.ofSeconds(5));
        assertEquals(1, held.size());
        Await.until(
                () -> {
                    System.gc();
                    return sent.body().get() == null;
                },
                Duration.ofSeconds(5));
        assertNull(sent.body().get(), "the body outlived its send while the upstream had yet to answer");
        held.get(0).end("event: response.completed\ndata: {}\n\n");
        assertEquals(200, await(sent.answer()).status());
    }

    private record Held(WeakReference<byte[]> body, Future<AdapterResponse> answer) {}

    /** The request is built here, so that nothing of it stays on the test's stack. */
    private Held sendHeld(Routes routes, String json) {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        MultiMap h = MultiMap.caseInsensitiveMultiMap()
                .add("X-Upstream-Endpoint", fx.mock().url() + "/hold/v1/responses")
                .add("X-Upstream-Key", "k");
        Future<AdapterResponse> answer = routes.handle(new AdapterRequest(
                HttpMethod.POST,
                "/openai/v1/responses",
                null,
                h,
                Buffers.wrap(body),
                Promise.<Void>promise().future()));
        return new Held(new WeakReference<>(body), answer);
    }

    /** The events as they came (the cases' "stream as received"), here split across chunks with pings between them. */
    @Test
    void streamRechunkedIntoEventsWithTheHeartbeat() throws Exception {
        fx.mock().on("/v1/responses", r -> {
            r.response().setChunked(true).setStatusCode(201).putHeader("Content-Type", "text/event-stream");
            r.response().write("event: a\ndata: {\"x\":");
            fx.vertx().setTimer(1500, t -> r.response().write("1}\n\nevent: b\r\ndata: 2\r\n\r\ndata: 3\n\n"));
            fx.vertx().setTimer(1600, t -> r.response().end("data: tail"));
        });
        AdapterResponse response = fx.send(
                routes("SSE_HEARTBEAT_INTERVAL", "1"),
                HttpMethod.POST,
                "/openai/v1/responses",
                """
                        {"model":"gpt-5","stream":true,"input":[]}""",
                "X-Upstream-Endpoint",
                v1(),
                "X-Upstream-Key",
                "k");
        String out = read(response);
        assertTrue(out.startsWith(": ping\n\n"), out);
        assertTrue(out.endsWith("event: a\ndata: {\"x\":1}\n\nevent: b\r\ndata: 2\r\n\r\ndata: 3\n\ndata: tail"), out);
        assertEquals(
                out.replace(": ping\n\n", ""),
                "event: a\ndata: {\"x\":1}\n\nevent: b\r\ndata: 2\r\n\r\ndata: 3\n\ndata: tail",
                "pings between events only");
    }

    @Test
    void bedrockApiKey() {
        String token = BedrockToken.token(
                new SigV4.Credentials("AKID", new Secret("s"), new Secret("tok/+=")),
                "eu-west-1",
                Instant.parse("2026-01-02T03:04:05Z"));
        assertTrue(token.startsWith("bedrock-api-key-"));
        String url = new String(
                Base64.getDecoder().decode(token.substring("bedrock-api-key-".length())), StandardCharsets.UTF_8);
        assertTrue(url.startsWith("bedrock.amazonaws.com/?"), url);
        Map<String, String> query = new HashMap<>();
        for (String pair : url.substring("bedrock.amazonaws.com/?".length()).split("&")) {
            query.put(pair.substring(0, pair.indexOf('=')), pair.substring(pair.indexOf('=') + 1));
        }
        assertEquals("CallWithBearerToken", query.get("Action"));
        assertEquals("AWS4-HMAC-SHA256", query.get("X-Amz-Algorithm"));
        assertEquals("AKID%2F20260102%2Feu-west-1%2Fbedrock%2Faws4_request", query.get("X-Amz-Credential"));
        assertEquals("20260102T030405Z", query.get("X-Amz-Date"));
        assertEquals("43200", query.get("X-Amz-Expires"));
        assertEquals("host", query.get("X-Amz-SignedHeaders"));
        assertEquals("tok%2F%2B%3D", query.get("X-Amz-Security-Token"));
        assertTrue(query.get("X-Amz-Signature").matches("[0-9a-f]{64}"), url);
        assertTrue(url.endsWith("&Version=1"));
    }

    @Test
    void poolExhausted() throws Exception {
        fx.mock().on("/v1/responses", r -> fx.vertx()
                .setTimer(2500, t -> r.response().end("{}")));
        Routes routes = routes("HTTP_MAX_CONNECTIONS", "1", "HTTP_POOL_TIMEOUT", "1");
        Future<AdapterResponse> held = routes.handle(new AdapterRequest(
                HttpMethod.POST,
                "/openai/v1/responses",
                null,
                MultiMap.caseInsensitiveMultiMap()
                        .add("X-Upstream-Endpoint", v1())
                        .add("X-Upstream-Key", "k"),
                Buffer.buffer(HI),
                Promise.<Void>promise().future()));
        AdapterResponse r = fx.send(
                routes,
                HttpMethod.POST,
                "/openai/v1/responses",
                HI,
                "X-Upstream-Endpoint",
                v1(),
                "X-Upstream-Key",
                "k");
        assertEquals(
                """
                        503 {"error":{"message":"No free upstream connection: the adapter connection pool is exhausted (HTTP_MAX_CONNECTIONS=1)","type":"internal_server_error","code":"503"}}""",
                r.status() + " " + read(r));
        assertEquals(200, await(held).status());
    }
}
