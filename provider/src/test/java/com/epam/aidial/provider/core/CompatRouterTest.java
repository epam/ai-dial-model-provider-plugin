package com.epam.aidial.provider.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.epam.aidial.provider.ModelProviders;
import com.epam.aidial.provider.api.AdapterRequest;
import com.epam.aidial.provider.api.AdapterResponse;
import com.epam.aidial.provider.api.Body;
import com.epam.aidial.provider.api.Handler;
import com.epam.aidial.provider.testing.Await;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CompatRouterTest {

    private final List<AdapterRequest> openaiRequests = new ArrayList<>();
    private final CompatRouter router = new CompatRouter(Map.of("/compat/openai", request -> {
        openaiRequests.add(request);
        return Future.succeededFuture(AdapterResponse.json(200, "{\"adapter\":\"openai\"}"));
    }));

    @Test
    void healthIsServedByTheProcess() {
        AdapterResponse response = handle(HttpMethod.GET, "/health");
        assertEquals(200, response.status());
        assertEquals("application/json", response.headers().get("Content-Type"));
        assertEquals("{\"status\":\"ok\"}", body(response));
    }

    @Test
    void healthTakesGetOnly() {
        for (HttpMethod method : new HttpMethod[] {HttpMethod.POST, HttpMethod.HEAD, HttpMethod.DELETE}) {
            AdapterResponse response = handle(method, "/health");
            assertEquals(405, response.status(), method.name());
            assertEquals("GET", response.headers().get("Allow"));
            assertEquals("{\"detail\":\"Method Not Allowed\"}", body(response));
        }
    }

    @Test
    void adapterGetsThePathBelowItsPrefix() {
        AdapterRequest request =
                request(HttpMethod.POST, "/compat/openai/openai/deployments/gpt-4/chat/completions", "api-version=1");
        assertEquals("{\"adapter\":\"openai\"}", body(await(router.handle(request))));
        AdapterRequest received = openaiRequests.getFirst();
        assertEquals("/openai/deployments/gpt-4/chat/completions", received.path());
        assertEquals("api-version=1", received.query());
        assertEquals(HttpMethod.POST, received.method());
        assertEquals(request.headers(), received.headers());
        assertEquals(request.aborted(), received.aborted());
    }

    @Test
    void adapterHealthIsTheAdaptersOwnPath() {
        handle(HttpMethod.GET, "/compat/openai/health");
        assertEquals("/health", openaiRequests.getFirst().path());
    }

    @Test
    void adapterRootIsSlash() {
        handle(HttpMethod.GET, "/compat/openai/");
        assertEquals("/", openaiRequests.getFirst().path());
    }

    @Test
    void anythingElseIsNotFound() {
        for (String path : new String[] {
            "/",
            "/compat",
            "/compat/",
            "/compat/openai",
            "/compat/nope/openai/deployments/x/chat/completions",
            "/openai/deployments/x/chat/completions",
            "/health/",
            "/compat/OpenAI/x",
            "/compat/open%61i/x"
        }) {
            AdapterResponse response = handle(HttpMethod.POST, path);
            assertEquals(404, response.status(), path);
            assertEquals("{\"detail\":\"Not Found\"}", body(response), path);
        }
        assertTrue(openaiRequests.isEmpty());
    }

    @Test
    void eachMountIsServedBelowItsOwnPrefix() {
        List<String> seen = new ArrayList<>();
        CompatRouter two = new CompatRouter(Map.of(
                "/compat/a", r -> record(seen, "a", r),
                "/other", r -> record(seen, "other", r),
                "/x/y/z", r -> record(seen, "xyz", r)));
        for (String path : List.of("/compat/a/p", "/other/p/q", "/x/y/z/", "/compat/a2/p", "/x/y", "/otherwise")) {
            await(two.handle(request(HttpMethod.GET, path, null)));
        }
        assertEquals(List.of("a /p", "other /p/q", "xyz /"), seen);
    }

    @Test
    void mountPrefixesArePathsAndNeverNested() {
        Handler none = r -> Future.succeededFuture(AdapterResponse.json(200, "{}"));
        for (String bad : List.of("", "/", "compat", "/compat/")) {
            assertThrows(IllegalArgumentException.class, () -> new CompatRouter(Map.of(bad, none)), bad);
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> new CompatRouter(Map.of("/compat", none, "/compat/openai", none)));
    }

    private static Future<AdapterResponse> record(List<String> seen, String mount, AdapterRequest request) {
        seen.add(mount + " " + request.path());
        return Future.succeededFuture(AdapterResponse.json(200, "{}"));
    }

    @Test
    void closeCompletesWhenEverythingIsReleased() {
        Promise<Void> released = Promise.promise();
        Future<Void> closed = new CompatRouter(Map.of(), released::future).close();
        assertFalse(closed.isComplete(), "not before what the adapters hold is released");
        released.complete();
        assertTrue(closed.succeeded());
    }

    @Test
    void onlyTheAnthropicPassthroughIsServed() throws Exception {
        Vertx vertx = Vertx.vertx();
        try {
            CompatRouter served = ModelProviders.create(vertx, Config.from(Map.of()));
            for (String adapter : ModelProviders.ADAPTERS) {
                AdapterResponse messages = served.handle(
                                request(HttpMethod.POST, "/compat/" + adapter + "/anthropic/v1/messages", null))
                        .toCompletionStage()
                        .toCompletableFuture()
                        .get(10, TimeUnit.SECONDS);
                assertTrue(messages.status() != 404, adapter + " serves /anthropic/v1/messages");
                for (String path : List.of(
                        "/openai/deployments/gpt-4/chat/completions",
                        "/openai/deployments/gpt-4/embeddings",
                        "/openai/deployments/gpt-4/tokenize",
                        "/openai/v1/responses/x/cancel/y",
                        "/openai/v1/responses/input_tokens/x",
                        "/openai/models",
                        "/chat/completions")) {
                    AdapterResponse r =
                            await(served.handle(request(HttpMethod.POST, "/compat/" + adapter + path, null)));
                    assertEquals(404, r.status(), adapter + path);
                    assertEquals("{\"detail\":\"Not Found\"}", body(r), adapter + path);
                }
            }
            for (String path :
                    List.of("/openai/v1/responses", "/openai/responses", "/openai/v1/responses/input_tokens")) {
                for (String adapter : ModelProviders.ADAPTERS) {
                    AdapterResponse r =
                            await(served.handle(request(HttpMethod.POST, "/compat/" + adapter + path, null)));
                    assertEquals(adapter.equals("openai") ? 502 : 404, r.status(), adapter + path);
                }
            }
            AdapterResponse dial = await(served.handle(
                    request(HttpMethod.POST, "/compat/dial/openai/deployments/x/chat/completions", null)));
            assertEquals(404, dial.status(), "the dial adapter is not served");
            served.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void translatorIsServedBelowItsOwnPrefix() throws Exception {
        Vertx vertx = Vertx.vertx();
        try {
            CompatRouter served = ModelProviders.create(vertx, Config.from(Map.of()));
            AdapterResponse messages =
                    await(served.handle(request(HttpMethod.POST, "/openaiResponses/anthropic/v1/messages", null)));
            assertEquals(400, messages.status(), "the translator answers: this body has no messages");
            assertEquals("application/json", messages.headers().get("Content-Type"));
            assertTrue(body(messages).contains("messages` is not set"), body(messages));
            AdapterResponse other =
                    await(served.handle(request(HttpMethod.GET, "/openaiResponses/anthropic/v1/messages", null)));
            assertEquals(404, other.status());
            assertEquals(
                    "{\"error\":{\"message\":\"Endpoint not supported\","
                            + "\"type\":\"not_found_error\"},\"type\":\"error\"}\n",
                    body(other));
            for (String path : List.of("/openaiResponses", "/openaiResponsesX/anthropic/v1/messages")) {
                AdapterResponse r = await(served.handle(request(HttpMethod.POST, path, null)));
                assertEquals(404, r.status(), path);
                assertEquals("{\"detail\":\"Not Found\"}", body(r), path);
            }
            served.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void closeClosesUpstreamConnections() throws Exception {
        Vertx vertx = Vertx.vertx();
        try {
            AtomicInteger opened = new AtomicInteger();
            AtomicInteger closed = new AtomicInteger();
            List<String> seen = new CopyOnWriteArrayList<>();
            HttpServer upstream = vertx.createHttpServer()
                    .connectionHandler(connection -> {
                        opened.incrementAndGet();
                        connection.closeHandler(v -> closed.incrementAndGet());
                    })
                    .requestHandler(r -> {
                        seen.add(r.path());
                        r.response()
                                .putHeader("Content-Type", "application/json")
                                .end("{\"id\":\"msg_1\"}");
                    });
            String url = "http://127.0.0.1:"
                    + upstream.listen(0, "127.0.0.1")
                            .toCompletionStage()
                            .toCompletableFuture()
                            .get(10, TimeUnit.SECONDS)
                            .actualPort();
            CompatRouter served = ModelProviders.create(vertx, Config.from(Map.of("ANTHROPIC_BASE_URL", url)));
            String[][] calls = {
                {"/compat/openai/anthropic/v1/messages", url + "/v1/messages", "{\"model\":\"m\"}"},
                {"/compat/vertexai/anthropic/v1/messages", null, "{\"model\":\"m\"}"},
                {"/compat/bedrock/anthropic/v1/messages", null, "{\"model\":\"m\"}"}
            };
            List<String> answers = new ArrayList<>();
            for (String[] call : calls) {
                MultiMap headers = MultiMap.caseInsensitiveMultiMap().set("X-Upstream-Key", "k");
                if (call[1] != null) {
                    headers.set("X-Upstream-Endpoint", call[1]);
                }
                AdapterResponse answer = served.handle(new AdapterRequest(
                                HttpMethod.POST,
                                call[0],
                                null,
                                headers,
                                Buffer.buffer(call[2]),
                                Promise.<Void>promise().future()))
                        .toCompletionStage()
                        .toCompletableFuture()
                        .get(10, TimeUnit.SECONDS);
                answers.add(call[0] + " " + answer.status()
                        + (answer.body() instanceof Body.Bytes b ? " " + b.buffer() : ""));
            }
            assertEquals(calls.length, opened.get(), "one connection per upstream client: " + seen + " " + answers);
            assertEquals(0, closed.get(), "kept alive");

            served.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            Await.until(() -> closed.get() >= calls.length, Duration.ofSeconds(10));
            assertEquals(calls.length, closed.get(), "every upstream client closed");
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void messagesAndResponsesOfTheOpenAiMountShareOneCap() throws Exception {
        Vertx vertx = Vertx.vertx();
        try {
            AtomicInteger seen = new AtomicInteger();
            // the first request is never answered: it holds the only slot
            HttpServer upstream = vertx.createHttpServer().requestHandler(r -> {
                if (seen.incrementAndGet() > 1) {
                    r.response().end("{}");
                }
            });
            String url = "http://127.0.0.1:"
                    + upstream.listen(0, "127.0.0.1")
                            .toCompletionStage()
                            .toCompletableFuture()
                            .get(10, TimeUnit.SECONDS)
                            .actualPort();
            CompatRouter served = ModelProviders.create(
                    vertx, Config.from(Map.of("HTTP_MAX_CONNECTIONS", "1", "HTTP_POOL_TIMEOUT", "1")));
            Promise<Void> clientGone = Promise.promise();
            served.handle(post(
                    "/compat/openai/anthropic/v1/messages",
                    url + "/v1/messages",
                    "{\"model\":\"m\"}",
                    clientGone.future()));
            Await.until(() -> seen.get() == 1, Duration.ofSeconds(10));

            AdapterResponse refused = served.handle(post(
                            "/compat/openai/openai/v1/responses",
                            url + "/v1/responses",
                            "{\"input\":\"hi\"}",
                            Promise.<Void>promise().future()))
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);

            assertEquals(503, refused.status());
            assertEquals(1, seen.get(), "the Responses call found no slot and was never sent");
            clientGone.complete();
            served.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    private static AdapterRequest post(String path, String endpoint, String body, Future<Void> aborted) {
        MultiMap headers =
                MultiMap.caseInsensitiveMultiMap().set("X-Upstream-Key", "k").set("X-Upstream-Endpoint", endpoint);
        return new AdapterRequest(HttpMethod.POST, path, null, headers, Buffer.buffer(body), aborted);
    }

    private AdapterResponse handle(HttpMethod method, String path) {
        return await(router.handle(request(method, path, null)));
    }

    private static AdapterRequest request(HttpMethod method, String path, String query) {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap().set("Api-Key", "k");
        return new AdapterRequest(
                method,
                path,
                query,
                headers,
                Buffer.buffer("{}"),
                Promise.<Void>promise().future());
    }

    private static AdapterResponse await(Future<AdapterResponse> future) {
        assertTrue(future.isComplete());
        assertNull(future.cause());
        return future.result();
    }

    private static String body(AdapterResponse response) {
        return ((Body.Bytes) response.body()).buffer().toString();
    }
}
