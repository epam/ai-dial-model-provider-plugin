package com.epam.aidial.provider.anthropic;

import static com.epam.aidial.provider.testing.MockUpstream.await;
import static com.epam.aidial.provider.testing.RoutesFixture.read;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.epam.aidial.provider.api.AdapterRequest;
import com.epam.aidial.provider.api.AdapterResponse;
import com.epam.aidial.provider.core.Buffers;
import com.epam.aidial.provider.core.Config;
import com.epam.aidial.provider.core.Routes;
import com.epam.aidial.provider.credentials.AzureAd;
import com.epam.aidial.provider.credentials.AzureTokenSource;
import com.epam.aidial.provider.credentials.AzureTokens;
import com.epam.aidial.provider.credentials.GoogleTokenSource;
import com.epam.aidial.provider.json.Json;
import com.epam.aidial.provider.testing.Await;
import com.epam.aidial.provider.testing.MockUpstream;
import com.epam.aidial.provider.testing.RoutesFixture;
import com.epam.aidial.provider.upstream.Limits;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** anthropic-messages and anthropic-upstreams for the vertexai and openai adapters. */
class AnthropicRoutesTest {

    private static final String PROMPT =
            """
            [ {"role":"user","content":[{"type":"text","text":"hi é","cache_control":{"type":"ephemeral","ttl":"1h"}}]} ]""";
    private static final String GCP = """
            {"region":"us-east5","project":"p","compatible_model_id":"m"}""";
    private static final String NOT_FOUND = "{\"detail\":\"Not Found\"}";

    private static String apiError(String message) {
        return "{\"error\":{\"message\":" + Json.encode(message) + ",\"type\":\"api_error\"},\"type\":\"error\"}\n";
    }

    private static String invalid(String message) {
        return "{\"error\":{\"message\":" + Json.encode(message)
                + ",\"type\":\"invalid_request_error\"},\"type\":\"error\"}\n";
    }

    @RegisterExtension
    final RoutesFixture fx = new RoutesFixture();

    private final AtomicInteger googleCalls = new AtomicInteger();
    private volatile boolean azureFails;

    @BeforeEach
    void start() {
        fx.mock()
                .reply(
                        "/",
                        200,
                        Map.of("Content-Type", "application/json", "Server", "gfe", "X-A", "1"),
                        "{\"id\":\"msg_1\"}");
    }

    private Routes vertex(int cap, long waitMillis) {
        Config config = Config.from(Map.of(
                "ANTHROPIC_VERTEX_BASE_URL",
                fx.mock().url() + "/",
                "ANTHROPIC_BASE_URL",
                fx.mock().url()));
        return routes(AnthropicTargets.vertexai(config), limits(cap, waitMillis));
    }

    private Routes openai() {
        return routes(AnthropicTargets.openai(), limits(10, 10_000));
    }

    private static Limits limits(int cap, long waitMillis) {
        return Limits.capped(
                cap, new Limits.PoolWait.Bounded(Duration.ofMillis(waitMillis)), Duration.ofMillis(600_000));
    }

    private Routes routes(AnthropicTargets.Resolver resolver, Limits limits) {
        UpstreamClient client =
                new UpstreamClient(fx.vertx(), Config.from(Map.of()).proxies(), limits);
        AzureTokenSource source = (scope, aborted) -> azureFails
                ? Future.failedFuture(
                        new AzureTokens.AzureAdException(new IllegalStateException("identity endpoint answered 400")))
                : Future.succeededFuture("aad-tok");
        GoogleTokenSource google = aborted -> {
            googleCalls.incrementAndGet();
            return Future.succeededFuture("tok");
        };
        return Routes.of(new AnthropicRoutes(
                "/compat/test",
                resolver,
                client,
                new AzureAd(source, "https://ai.azure.com/.default"),
                google,
                RoutesFixture.CLOCK));
    }

    @Test
    void vertexStreaming() throws Exception {
        String sse = """
                event: message_start\ndata: {"type":"message_start"}\n\n""";
        fx.mock().reply("/projects/", 200, Map.of("Content-Type", "text/event-stream"), sse);
        MultiMap h = MultiMap.caseInsensitiveMultiMap()
                .add("X-Upstream-Extra-Data", GCP)
                .add("Anthropic-Beta", "prompt-caching-scope-2026-01-05, context-1m-2025-08-07")
                .add("X-Other", "dropped");
        AdapterResponse response = await(vertex(10, 10_000)
                .handle(new AdapterRequest(
                        HttpMethod.POST,
                        "/anthropic/v1/messages",
                        null,
                        h,
                        Buffer.buffer("{\"model\":\"claude-x\",\"stream\":true,\"max_tokens\":5,\"messages\":" + PROMPT
                                + "}"),
                        Promise.<Void>promise().future())));

        assertEquals(200, response.status());
        assertEquals(sse, read(response));
        MockUpstream.Seen seen = fx.mock().last();
        assertEquals(
                "/projects/p/locations/us-east5/publishers/anthropic/models/claude-x:streamRawPredict", seen.path());
        assertEquals(
                "{\"stream\":true,\"max_tokens\":5,\"messages\":" + PROMPT
                        + ",\"anthropic_version\":\"vertex-2023-10-16\"}",
                seen.body());
        assertEquals("Bearer tok", seen.headers().get("Authorization"));
        assertEquals("context-1m-2025-08-07", seen.headers().get("Anthropic-Beta"));
        assertNull(seen.headers().get("X-Other"));
        assertNull(seen.headers().get("Anthropic-Version"));
        assertEquals(
                "prefix.body.messages[0].content[0]", response.headers().get(AdapterResponse.CACHE_BREAKPOINT_HEADER));
        assertEquals("4600", response.headers().get(AdapterResponse.CACHE_EXPIRE_AT_HEADER));
    }

    /**
     * anthropic-upstreams "The vertexai adapter reads the Vertex AI region and project" (Defaults from the environment,
     * GCP_DEFAULT_REGION before DEFAULT_REGION, Empty GCP_DEFAULT_REGION).
     */
    @Test
    void vertexRegionDefaults() throws Exception {
        String[][] cases = {
            {"", "us-east5", "us-east5"}, {"europe-west1", "us-east5", "europe-west1"}, {null, "us-east5", "us-east5"}
        };
        for (String[] c : cases) {
            Map<String, String> settings = new HashMap<>(Map.of(
                    "ANTHROPIC_VERTEX_BASE_URL", fx.mock().url() + "/", "DEFAULT_REGION", c[1], "GCP_PROJECT_ID", "p"));
            if (c[0] != null) {
                settings.put("GCP_DEFAULT_REGION", c[0]);
            }
            Routes routes = routes(AnthropicTargets.vertexai(Config.from(settings)), limits(10, 10_000));
            fx.post(routes, "/anthropic/v1/messages", "{\"model\":\"m\",\"messages\":[]}", Map.of());
            assertEquals(
                    "/projects/p/locations/" + c[2] + "/publishers/anthropic/models/m:rawPredict",
                    fx.mock().last().path(),
                    String.join(",", String.valueOf(c[0])));
        }
    }

    @Test
    void vertexModelWithLoneSurrogateIsRefused() throws Exception {
        Map<String, String> gcp = Map.of("X-Upstream-Extra-Data", "{\"region\":\"global\",\"project\":\"p\"}");
        AdapterResponse response =
                fx.post(vertex(10, 10_000), "/anthropic/v1/messages", "{\"model\":\"a\\ud800b\",\"messages\":[]}", gcp);
        assertEquals(400, response.status());
        assertEquals(invalid("model: Input should be valid Unicode text"), read(response));
        assertEquals(List.of(), fx.mock().seen(), "nothing sent upstream");
        assertEquals(0, googleCalls.get(), "and no token fetched for it");
    }

    @Test
    void anthropicVersion() throws Exception {
        Map<String, String> gcp = Map.of("X-Upstream-Extra-Data", "{\"region\":\"global\",\"project\":\"p\"}");
        fx.post(vertex(10, 10_000), "/anthropic/v1/messages", "{\"model\":\"m\",\"messages\":[]}", gcp);
        assertNull(fx.mock().last().headers().get("Anthropic-Version"), "vertex: in the body, not a header");
        fx.post(vertex(10, 10_000), "/anthropic/v1/messages/count_tokens", "{\"model\":\"m\",\"messages\":[]}", gcp);
        assertNull(fx.mock().last().headers().get("Anthropic-Version"));
        assertEquals(
                """
                        {"model":"m","messages":[],"anthropic_version":"vertex-2023-10-16"}""",
                fx.mock().last().body(),
                "count_tokens keeps the model");
        assertTrue(fx.mock().last().path().endsWith("/count-tokens:rawPredict"));

        fx.post(vertex(10, 10_000), "/anthropic/v1/messages", "{\"model\":\"m\"}", Map.of("X-Upstream-Key", "k"));
        assertEquals("2023-06-01", fx.mock().last().headers().get("Anthropic-Version"));
        fx.post(
                vertex(10, 10_000),
                "/anthropic/v1/messages",
                "{\"model\":\"m\"}",
                Map.of("X-Upstream-Key", "k", "anthropic-version", ""));
        assertEquals("", fx.mock().last().headers().get("Anthropic-Version"));
        fx.post(
                vertex(10, 10_000),
                "/anthropic/v1/messages",
                "{\"model\":\"m\"}",
                Map.of("X-Upstream-Key", "k", "anthropic-version", "2023-01-01"));
        assertEquals("2023-01-01", fx.mock().last().headers().get("Anthropic-Version"));
    }

    @Test
    void betaHeaderLines() throws Exception {
        MultiMap h = MultiMap.caseInsensitiveMultiMap()
                .add("X-Upstream-Extra-Data", GCP)
                .add("Anthropic-Beta", "a, b")
                .add("Anthropic-Beta", "c");
        await(vertex(10, 10_000)
                .handle(new AdapterRequest(
                        HttpMethod.POST,
                        "/anthropic/v1/messages",
                        null,
                        h,
                        Buffer.buffer("{\"model\":\"m\"}"),
                        Promise.<Void>promise().future())));
        assertEquals(List.of("a,b,c"), fx.mock().last().headers().getAll("Anthropic-Beta"));

        h = MultiMap.caseInsensitiveMultiMap()
                .add("X-Upstream-Key", "k")
                .add("Anthropic-Beta", "a, b")
                .add("Anthropic-Beta", "c");
        await(vertex(10, 10_000)
                .handle(new AdapterRequest(
                        HttpMethod.POST,
                        "/anthropic/v1/messages",
                        null,
                        h,
                        Buffer.buffer("{\"model\":\"m\"}"),
                        Promise.<Void>promise().future())));
        assertEquals(
                List.of("a, b", "c"),
                fx.mock().last().headers().getAll("Anthropic-Beta"),
                "the platform gets the lines as received");
    }

    @Test
    void foundryForwardsBodyBytes() throws Exception {
        String body =
                """
                        { "model" : "claude-x", "messages":[ {"role":"user","content":[{"type":"text","text":"hi é"}]} ] }""";
        AdapterResponse response = fx.post(
                vertex(10, 10_000),
                "/anthropic/v1/messages",
                body,
                Map.of(
                        "X-Upstream-Endpoint",
                        fx.mock().url() + "/anthropic/v1/messages",
                        "X-Upstream-Key",
                        "fk",
                        "Accept-Encoding",
                        "gzip"));
        MockUpstream.Seen seen = fx.mock().last();
        assertEquals(body, seen.body());
        assertEquals("/anthropic/v1/messages", seen.path());
        assertEquals("fk", seen.headers().get("Api-Key"));
        assertEquals("fk", seen.headers().get("X-Api-Key"));
        assertEquals("gzip", seen.headers().get("Accept-Encoding"));
        assertEquals("application/json", seen.headers().get("Content-Type"));
        assertEquals("application/json", seen.headers().get("Accept"));
        assertEquals(UpstreamClient.USER_AGENT, seen.headers().get("User-Agent"));
        assertEquals("1", response.headers().get("X-A"));
    }

    @Test
    void advisorToolAndBetaDroppedOnFoundry() throws Exception {
        Map<String, String> foundry = Map.of(
                "X-Upstream-Endpoint",
                fx.mock().url() + "/anthropic/v1/messages",
                "X-Upstream-Key",
                "k",
                "Anthropic-Beta",
                "advisor-tool-2026-03-01");
        fx.post(
                vertex(10, 10_000),
                "/anthropic/v1/messages",
                """
                        {"model":"m","tools":[{"type":"advisor_20260301","name":"advisor"},{ "name":"get", "input_schema":{"type":"object"}}],"x" : 1}""",
                foundry);
        assertEquals(
                """
                        {"model":"m","tools":[{ "name":"get", "input_schema":{"type":"object"}}],"x":1}""",
                fx.mock().last().body());
        assertFalse(fx.mock().last().headers().contains("Anthropic-Beta"), "nothing left of the header");
    }

    @Test
    void vertexModelEscaped() throws Exception {
        fx.post(
                vertex(10, 10_000),
                "/anthropic/v1/messages",
                "{\"model\":\"a/b c\",\"messages\":[]}",
                Map.of("X-Upstream-Extra-Data", GCP));
        assertEquals(
                "/projects/p/locations/us-east5/publishers/anthropic/models/a%2Fb%20c:rawPredict",
                fx.mock().last().uri());
    }

    @Test
    void modelMustBeNonEmptyString() throws Exception {
        Map<String, String> platform = Map.of("X-Upstream-Key", "k");
        Map<String, String> gcp = Map.of("X-Upstream-Extra-Data", GCP);
        Map<String, String> bodies = new LinkedHashMap<>();
        bodies.put("{\"messages\":[]}", "model: Field required");
        bodies.put("{\"model\":null,\"messages\":[]}", "model: Field required");
        bodies.put("{\"model\":\"\",\"messages\":[]}", "model: String should have at least 1 character");
        bodies.put("{\"model\":1,\"messages\":[]}", "model: Input should be a valid string");
        bodies.put("{\"model\":true,\"messages\":[]}", "model: Input should be a valid string");
        bodies.put("{\"model\":[\"a\"],\"messages\":[]}", "model: Input should be a valid string");
        bodies.put("{\"model\":{\"k\":\"v\"},\"messages\":[]}", "model: Input should be a valid string");
        for (String route : List.of("/messages", "/messages/count_tokens")) {
            bodies.forEach((body, message) -> {
                for (Map<String, String> headers : List.of(platform, gcp)) {
                    try {
                        AdapterResponse response = fx.post(vertex(10, 10_000), "/anthropic/v1" + route, body, headers);
                        assertEquals(400, response.status(), route + " " + body);
                        assertEquals(invalid(message), read(response), route + " " + body);
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                }
            });
        }
        assertTrue(fx.mock().seen().isEmpty(), "nothing is sent upstream");
        assertEquals(0, googleCalls.get(), "no token is fetched for a request that is refused");
    }

    @Test
    void streamMustBeBoolean() throws Exception {
        Map<String, String> platform = Map.of("X-Upstream-Key", "k");
        for (String stream : List.of("\"false\"", "1", "0", "[]", "{}", "\"\"")) {
            AdapterResponse response = fx.post(
                    vertex(10, 10_000),
                    "/anthropic/v1/messages",
                    "{\"model\":\"m\",\"stream\":" + stream + "}",
                    platform);
            assertEquals(400, response.status(), stream);
            assertEquals(
                    invalid("\"stream\" must be a boolean, but is "
                            + Map.of(
                                            "\"false\"",
                                            "a string",
                                            "1",
                                            "a number",
                                            "0",
                                            "a number",
                                            "[]",
                                            "an array",
                                            "{}",
                                            "an object",
                                            "\"\"",
                                            "a string")
                                    .get(stream)),
                    read(response),
                    stream);
        }
        assertTrue(fx.mock().seen().isEmpty(), "nothing is sent upstream");

        // absent, null and false do not stream; count_tokens has no stream flag and takes any value
        fx.mock().reply("/", 200, Map.of("Content-Type", "application/json"), "{\"id\":\"msg_1\"}");
        for (String body : List.of(
                "{\"model\":\"m\"}", "{\"model\":\"m\",\"stream\":null}", "{\"model\":\"m\",\"stream\":false}")) {
            AdapterResponse response = fx.post(vertex(10, 10_000), "/anthropic/v1/messages", body, platform);
            assertEquals(200, response.status(), body);
            assertEquals("{\"id\":\"msg_1\"}", read(response), body);
        }
        assertEquals(
                200,
                fx.post(
                                vertex(10, 10_000),
                                "/anthropic/v1/messages/count_tokens",
                                "{\"model\":\"m\",\"stream\":1}",
                                platform)
                        .status());
    }

    @Test
    void upstreamErrorRelayed() throws Exception {
        fx.mock()
                .reply(
                        "/anthropic/",
                        429,
                        Map.of("Retry-After", "7", "Content-Type", "application/json", "X-Junk", "1"),
                        "{\"type\":\"error\"}");
        AdapterResponse response = fx.post(
                vertex(10, 10_000),
                "/anthropic/v1/messages",
                "{\"model\":\"m\",\"stream\":true}",
                Map.of("X-Upstream-Endpoint", fx.mock().url() + "/anthropic/v1/messages", "X-Upstream-Key", "k"));
        assertEquals(429, response.status());
        assertEquals("7", response.headers().get("Retry-After"));
        assertEquals("application/json", response.headers().get("Content-Type"));
        assertNull(response.headers().get("X-Junk"));
        assertEquals("{\"type\":\"error\"}", read(response));

        fx.mock().reply("/anthropic/", 307, Map.of("Location", "http://x", "Retry-After-Ms", "5"), "");
        response = fx.post(
                vertex(10, 10_000),
                "/anthropic/v1/messages",
                "{\"model\":\"m\"}",
                Map.of("X-Upstream-Endpoint", fx.mock().url() + "/anthropic/v1/messages", "X-Upstream-Key", "k"));
        assertEquals(307, response.status());
        assertEquals("5", response.headers().get("Retry-After-Ms"));
        assertNull(response.headers().get("Location"));
    }

    /** operations "Request body held once": not while the upstream has yet to answer. */
    @Test
    void bodyReleasedBeforeTheAnswer() throws Exception {
        List<HttpServerResponse> held = new CopyOnWriteArrayList<>();
        fx.mock().on("/hold/", r -> held.add(r.response().putHeader("Content-Type", "application/json")));
        Routes routes = openai();
        Sent sent = sendHeld(routes);
        Await.until(() -> !held.isEmpty(), Duration.ofSeconds(5));
        assertEquals(1, held.size());
        Await.until(
                () -> {
                    System.gc();
                    return sent.body().get() == null;
                },
                Duration.ofSeconds(5));
        assertNull(sent.body().get(), "the body outlived its send while the upstream had yet to answer");
        held.get(0).end("{\"id\":\"msg_1\"}");
        assertEquals(200, await(sent.answer()).status());
    }

    private record Sent(WeakReference<byte[]> body, Future<AdapterResponse> answer) {}

    /** The request is built here, so that nothing of it stays on the test's stack. */
    private Sent sendHeld(Routes routes) {
        byte[] body = ("{\"model\":\"m\",\"max_tokens\":1,\"messages\":[{\"role\":\"user\",\"content\":\""
                        + "x".repeat(100_000) + "\"}]}")
                .getBytes(StandardCharsets.UTF_8);
        MultiMap h = MultiMap.caseInsensitiveMultiMap()
                .add("X-Upstream-Endpoint", fx.mock().url() + "/hold/v1/messages")
                .add("X-Upstream-Key", "k");
        Future<AdapterResponse> answer = routes.handle(new AdapterRequest(
                HttpMethod.POST,
                "/anthropic/v1/messages",
                null,
                h,
                Buffers.wrap(body),
                Promise.<Void>promise().future()));
        return new Sent(new WeakReference<>(body), answer);
    }

    /** The body is decoded before cache_control is looked for: an escaped member name is a mark too. */
    @Test
    void escapedCacheControlMarks() throws Exception {
        fx.mock().reply("/anthropic/", 200, Map.of(), "{}");
        String prompt = PROMPT.replace("\"cache_control\"", "\"cache\\u005fcontrol\""); // JSON's escape, not Java's
        AdapterResponse response = fx.post(
                vertex(10, 10_000),
                "/anthropic/v1/messages",
                "{\"model\":\"m\",\"messages\":" + prompt + "}",
                Map.of("X-Upstream-Endpoint", fx.mock().url() + "/anthropic/v1/messages", "X-Upstream-Key", "k"));
        assertEquals(200, response.status());
        assertEquals(
                "prefix.body.messages[0].content[0]", response.headers().get(AdapterResponse.CACHE_BREAKPOINT_HEADER));
    }

    @Test
    void cacheHeadersOn200Only() throws Exception {
        fx.mock().reply("/anthropic/", 201, Map.of(), "{}");
        AdapterResponse response = fx.post(
                vertex(10, 10_000),
                "/anthropic/v1/messages",
                "{\"model\":\"m\",\"messages\":" + PROMPT + "}",
                Map.of("X-Upstream-Endpoint", fx.mock().url() + "/anthropic/v1/messages", "X-Upstream-Key", "k"));
        assertEquals(201, response.status());
        assertNull(response.headers().get(AdapterResponse.CACHE_BREAKPOINT_HEADER));
    }

    @Test
    void answeredWithoutTheUpstream() throws Exception {
        AdapterResponse batches = fx.post(
                vertex(10, 10_000),
                "/anthropic/v1/messages/batches",
                "{\"requests\":[]}",
                Map.of("X-Upstream-Extra-Data", GCP));
        assertEquals(404, batches.status());
        assertEquals(NOT_FOUND, read(batches));

        AdapterResponse rejected = fx.post(
                vertex(10, 10_000), "/anthropic/v1/messages", "{\"model\":", Map.of("X-Upstream-Extra-Data", "[]"));
        assertEquals(500, rejected.status());
        assertEquals("text/plain; charset=utf-8", rejected.headers().get("Content-Type"));
        assertEquals("Internal Server Error", read(rejected));

        byte[] notUtf8 = {'{', '"', 'm', 'o', 'd', 'e', 'l', '"', ':', '"', 'm', (byte) 0xff, '"', '}'};
        AdapterResponse refused =
                fx.post(vertex(10, 10_000), "/anthropic/v1/messages", notUtf8, Map.of("X-Upstream-Extra-Data", GCP));
        assertEquals(400, refused.status());
        assertEquals(invalid("The request body is not valid JSON: Invalid UTF-8 start byte 0xff"), read(refused));

        AdapterResponse noModel = fx.post(
                vertex(10, 10_000),
                "/anthropic/v1/messages",
                "{\"messages\":[]}",
                Map.of("X-Upstream-Extra-Data", GCP));
        assertEquals(400, noModel.status());
        assertEquals(invalid("model: Field required"), read(noModel));

        AdapterResponse noKey = fx.post(
                openai(),
                "/anthropic/v1/messages",
                "{}",
                Map.of("X-Upstream-Endpoint", fx.mock().url() + "/v1/messages"));
        assertEquals(502, noKey.status());
        assertEquals(
                apiError("X-UPSTREAM-KEY header is missing in the request."),
                read(noKey),
                "a refused upstream, before the body");

        for (String endpoint : new String[] {fx.mock().url() + "/messages", ""}) {
            AdapterResponse badEndpoint =
                    fx.post(openai(), "/anthropic/v1/messages", "{}", Map.of("X-Upstream-Endpoint", endpoint));
            assertEquals(502, badEndpoint.status(), endpoint);
            assertEquals(apiError("Invalid upstream endpoint format"), read(badEndpoint), endpoint);
        }

        assertTrue(fx.mock().seen().isEmpty());
        assertEquals(0, googleCalls.get(), "no token fetched for a request refused before it is signed");
    }

    @Test
    void bodyThatIsNotAnObject() throws Exception {
        Map<String, String> platform = Map.of("X-Upstream-Key", "k");
        byte[] notUtf8 = {'{', '"', 'm', 'o', 'd', 'e', 'l', '"', ':', '"', 'm', (byte) 0xff, '"', '}'};
        byte[] bomArray = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, '[', '1', ']'};
        Map<Object, String> bodies = new LinkedHashMap<>();
        bodies.put("", "The request body is empty");
        bodies.put(
                "{\"model\":",
                "The request body is not valid JSON: Unexpected end-of-input within/between Object entries");
        bodies.put("{\"model\":\"m\"} {}", "The request body is not valid JSON: data follows the object");
        bodies.put("null", "The request body must be a JSON object, not null");
        bodies.put(
                "NaN",
                "The request body is not valid JSON: Non-standard token 'NaN': enable "
                        + "`JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS` to allow");
        bodies.put("[1]", "The request body must be a JSON object, not an array");
        bodies.put("\"m\"", "The request body must be a JSON object, not a string");
        bodies.put("1", "The request body must be a JSON object, not a number");
        bodies.put("true", "The request body must be a JSON object, not a boolean");
        bodies.put(
                """
                        {"model":"a","model":"m","max_tokens":5,"messages":[]}""",
                "The request body is not valid JSON: Duplicate field 'model'");
        bodies.put(notUtf8, "The request body is not valid JSON: Invalid UTF-8 start byte 0xff");
        bodies.put(bomArray, "The request body must be a JSON object, not an array");
        for (String route : List.of("/messages", "/messages/count_tokens")) {
            bodies.forEach((body, message) -> {
                try {
                    AdapterResponse response = body instanceof byte[] bytes
                            ? fx.post(vertex(10, 10_000), "/anthropic/v1" + route, bytes, platform)
                            : fx.post(vertex(10, 10_000), "/anthropic/v1" + route, (String) body, platform);
                    assertEquals(400, response.status(), route);
                    assertEquals(invalid(message), read(response), route);
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
        }
        assertTrue(fx.mock().seen().isEmpty(), "nothing is sent upstream");

        fx.post(
                vertex(10, 10_000),
                "/anthropic/v1/messages",
                new byte[] {
                    (byte) 0xEF,
                    (byte) 0xBB,
                    (byte) 0xBF,
                    '{',
                    '"',
                    'm',
                    'o',
                    'd',
                    'e',
                    'l',
                    '"',
                    ':',
                    '"',
                    'm',
                    '"',
                    ',',
                    '"',
                    'x',
                    '"',
                    ':',
                    '2',
                    '}'
                },
                platform);
        assertEquals("{\"model\":\"m\",\"x\":2}", fx.mock().last().body(), "the BOM of an object is skipped");
    }

    @Test
    void azureAdFailureIs401() throws Exception {
        azureFails = true;
        AdapterResponse response = fx.post(
                vertex(10, 10_000),
                "/anthropic/v1/messages",
                "{\"model\":\"m\",\"stream\":true}",
                Map.of("X-Upstream-Endpoint", fx.mock().url() + "/anthropic/v1/messages"));
        assertEquals(401, response.status());
        assertEquals(
                """
                        {"error":{"message":"Authentication failed","type":"authentication_error"},"type":"error"}\n""",
                read(response));
        assertTrue(fx.mock().seen().isEmpty());

        azureFails = false;
        fx.post(
                vertex(10, 10_000),
                "/anthropic/v1/messages",
                "{\"model\":\"m\"}",
                Map.of("X-Upstream-Endpoint", fx.mock().url() + "/anthropic/v1/messages"));
        assertEquals("Bearer aad-tok", fx.mock().last().headers().get("Authorization"));
    }

    @Test
    void poolExhaustedIs503() throws Exception {
        fx.mock().on("/anthropic/", r -> fx.vertx()
                .setTimer(1500, t -> r.response().end("{}")));
        Routes routes = vertex(1, 300);
        Map<String, String> foundry =
                Map.of("X-Upstream-Endpoint", fx.mock().url() + "/anthropic/v1/messages", "X-Upstream-Key", "k");
        MultiMap h = MultiMap.caseInsensitiveMultiMap();
        foundry.forEach(h::add);
        Future<AdapterResponse> holder = routes.handle(new AdapterRequest(
                HttpMethod.POST,
                "/anthropic/v1/messages",
                null,
                h,
                Buffer.buffer("{\"model\":\"m\"}"),
                Promise.<Void>promise().future()));
        assertTrue(Await.until(() -> !fx.mock().seen().isEmpty(), Duration.ofSeconds(10)), "the request never arrived");
        AdapterResponse response = fx.post(routes, "/anthropic/v1/messages/count_tokens", "{\"model\":\"m\"}", foundry);
        assertEquals(503, response.status());
        assertEquals(
                """
                        {"error":{"message":"No free upstream connection: the adapter connection pool is exhausted","type":"api_error"},"type":"error"}\n""",
                read(response));
        assertEquals(200, await(holder).status());
        assertEquals(1, fx.mock().seen().size());
    }

    @Test
    void openaiTargets() throws Exception {
        AnthropicTargets.Resolver openai = AnthropicTargets.openai();
        Target.Foundry foundry = assertInstanceOf(
                Target.Foundry.class,
                openai.resolve(request(
                        Map.of("X-Upstream-Endpoint", "https://r.services.ai.azure.com/anthropic/v1/messages"))));
        assertNull(foundry.key());
        Target.Platform platform = assertInstanceOf(
                Target.Platform.class,
                openai.resolve(request(Map.of(
                        "X-Upstream-Endpoint",
                        "https://api.example.com/v1/messages/count_tokens",
                        "X-Upstream-Key",
                        ""))));
        assertEquals("https://api.example.com", platform.baseUrl());
        assertNotNull(platform.key(), "a present but empty key is a key");
        assertInstanceOf(
                Target.Platform.class,
                openai.resolve(request(Map.of(
                        "X-Upstream-Endpoint",
                        "https://my_res.openai.azure.com/anthropic/v1/messages",
                        "X-Upstream-Key",
                        "k"))),
                "java.net.URI reads no host name from an underscore");
    }

    private static AdapterRequest request(Map<String, String> headers) {
        MultiMap h = MultiMap.caseInsensitiveMultiMap();
        headers.forEach(h::add);
        return new AdapterRequest(
                HttpMethod.POST,
                "/anthropic/v1/messages",
                null,
                h,
                Buffer.buffer(),
                Promise.<Void>promise().future());
    }
}
