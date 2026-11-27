package com.epam.aidial.provider.plugin;

import static com.epam.aidial.provider.testing.MockUpstream.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.epam.aidial.core.plugin.Call;
import com.epam.aidial.core.plugin.HostDelegate;
import com.epam.aidial.core.plugin.Plugin;
import com.epam.aidial.core.plugin.PluginProvider;
import com.epam.aidial.core.plugin.Request;
import com.epam.aidial.core.plugin.Response;
import com.epam.aidial.provider.ModelProviders;
import com.epam.aidial.provider.core.CompatRouter;
import com.epam.aidial.provider.core.Config;
import com.epam.aidial.provider.core.HttpServerShim;
import com.epam.aidial.provider.core.ModelProviderVerticle;
import com.epam.aidial.provider.testing.MockUpstream;
import com.epam.aidial.provider.testing.Requests;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.streams.ReadStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** embedded-mode: DIAL Core's plugin, driven as DIAL Core drives it (EmbeddedProxyRequest, EmbeddedProxyResponse). */
class ModelProviderPluginTest {

    private static final String MESSAGES = "{\"model\":\"m\",\"max_tokens\":5,\"messages\":[]}";
    private static final String STREAMED = "{\"model\":\"m\",\"stream\":true,\"max_tokens\":5,\"messages\":[]}";

    private Vertx vertx;
    private Context context;
    private MockUpstream mock;
    private final List<Plugin> plugins = new ArrayList<>();
    // set while the test calls into an answer's body, as DIAL Core does: nothing may be emitted then
    private boolean inCall;
    private final List<String> emittedInCall = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws Exception {
        vertx = Vertx.vertx();
        context = vertx.getOrCreateContext();
        mock = new MockUpstream(vertx)
                .reply(
                        "/",
                        200,
                        Map.of("Content-Type", "application/json", "Server", "gfe", "X-A", "1"),
                        "{\"id\":\"msg_1\"}");
    }

    @AfterEach
    void stop() throws Exception {
        plugins.forEach(Plugin::close);
        await(vertx.close());
        assertEquals(List.of(), emittedInCall, "emitted from within a call DIAL Core made to the body");
    }

    @Test
    void invalidSettingIsRejected() {
        PluginProvider.Problem problem = single(new ModelProviderPluginProvider(Map.of())
                .validate(settings("adapter", "openai", "HTTP_MAX_CONNECTIONS", "many")));
        assertEquals(
                new PluginProvider.Problem("HTTP_MAX_CONNECTIONS", "HTTP_MAX_CONNECTIONS must be an integer"), problem);

        ModelProviderPluginProvider badEnvironment =
                new ModelProviderPluginProvider(Map.of("HTTP_MAX_CONNECTIONS", "many"));
        assertEquals(
                "",
                single(badEnvironment.validate(settings("adapter", "openai"))).path(),
                "not a setting of the entry");
        assertEquals(
                List.of(),
                badEnvironment.validate(settings("adapter", "openai", "HTTP_MAX_CONNECTIONS", "10")),
                "the entry wins");
        assertEquals(
                List.of(),
                badEnvironment.validate(settings("adapter", "openai").putNull("HTTP_MAX_CONNECTIONS")),
                "null unsets it");
        assertEquals(
                List.of(),
                new ModelProviderPluginProvider(Map.of())
                        .validate(settings("adapter", "openai").put("HTTP_MAX_CONNECTIONS", 10)));

        PluginProvider.Problem json = single(new ModelProviderPluginProvider(Map.of())
                .validate(settings("adapter", "bedrock", "AWS_SESSION_TAGS", "x")));
        assertEquals("AWS_SESSION_TAGS", json.path(), "a start-time check, whatever the entry's adapter");
        assertTrue(json.message().contains("must be a JSON object of strings"), json.message());
    }

    @Test
    void anEntrysValuesAreTheTextTheEnvironmentWouldHold() {
        ModelProviderPluginProvider provider = new ModelProviderPluginProvider(Map.of());
        ObjectNode object = settings("adapter", "bedrock");
        object.putObject("AWS_SESSION_TAGS").put("team", "a");
        assertEquals(List.of(), provider.validate(object), "an object is its JSON");

        assertEquals(
                new PluginProvider.Problem("HTTP_MAX_CONNECTIONS", "HTTP_MAX_CONNECTIONS must be an integer"),
                single(provider.validate(settings("adapter", "openai").put("HTTP_MAX_CONNECTIONS", 1.5))),
                "a number is its text");
        assertEquals(
                "HTTP_MAX_CONNECTIONS",
                single(provider.validate(settings("adapter", "openai").put("HTTP_MAX_CONNECTIONS", true)))
                        .path(),
                "a boolean is its text");
    }

    @Test
    void settingsFromTheEntryThenTheEnvironment() throws Exception {
        MultiMap keyed = MultiMap.caseInsensitiveMultiMap().add("X-Upstream-Key", "k");
        Plugin fromEntry = plugin(
                Map.of("ANTHROPIC_BASE_URL", "http://127.0.0.1:1"),
                settings("adapter", "vertexai", "ANTHROPIC_BASE_URL", mock.url()));
        assertEquals(
                200,
                answer(fromEntry, request("/anthropic/v1/messages", keyed, MESSAGES), new Host())
                        .statusCode());
        assertEquals("/v1/messages", mock.last().path());
        assertEquals("k", mock.last().headers().get("X-Api-Key"));

        Plugin fromEnvironment = plugin(Map.of("ANTHROPIC_BASE_URL", mock.url()), settings("adapter", "vertexai"));
        assertEquals(
                200,
                answer(fromEnvironment, request("/anthropic/v1/messages", keyed, MESSAGES), new Host())
                        .statusCode());
        assertEquals(2, mock.seen("/v1/messages").size());
    }

    @Test
    void requestServedAsTheStandaloneServerServesIt() throws Exception {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap()
                .add("X-Upstream-Endpoint", mock.url() + "/v1/messages")
                .add("X-Upstream-Key", "k")
                .add("Anthropic-Beta", "a")
                .add("Api-Key", "per-request")
                .add("Content-Type", "application/json");
        Response answer = answer(
                plugin(Map.of(), settings("adapter", "openai")),
                request("/anthropic/v1/messages?beta=true", headers, MESSAGES),
                new Host());
        String body = read(answer);
        MockUpstream.Seen inProcess = mock.last();

        CompatRouter router = ModelProviders.create(vertx, Config.from(Map.of()));
        HttpServer server = await(vertx.createHttpServer(ModelProviderVerticle.serverOptions("127.0.0.1", 0))
                .requestHandler(new HttpServerShim(router, Requests.NO_CORE))
                .listen());
        Buffer[] read = new Buffer[1]; // read in the same chain: a body asked for later may have gone by
        HttpClientResponse standalone = await(vertx.createHttpClient()
                .request(new RequestOptions()
                        .setMethod(HttpMethod.POST)
                        .setHost("127.0.0.1")
                        .setPort(server.actualPort())
                        .setURI("/compat/openai/anthropic/v1/messages?beta=true")
                        .setHeaders(headers))
                .compose(request -> request.send(Buffer.buffer(MESSAGES)))
                .compose(response -> response.body().map(b -> {
                    read[0] = b;
                    return response;
                })));
        String standaloneBody = read[0].toString();
        MockUpstream.Seen overHttp = mock.last();
        router.close();

        assertEquals(
                overHttp.method() + " " + overHttp.uri() + " " + overHttp.body(),
                inProcess.method() + " " + inProcess.uri() + " " + inProcess.body());
        assertEquals(
                overHttp.headers().entries().toString(),
                inProcess.headers().entries().toString());
        assertEquals(standalone.statusCode(), answer.statusCode());
        assertEquals(withoutFraming(standalone.headers()), withoutFraming(answer.headers()));
        assertEquals(standaloneBody, body);
        assertEquals(200, answer.statusCode());
        assertEquals("1", answer.headers().get("X-A"));
        assertNull(answer.headers().get("Server"), "not forwarded by the standalone server either");
    }

    @Test
    void bugIsTheStandaloneServersAnswer() throws Exception {
        Plugin throwing =
                new ModelProviderPlugin(vertx, "/compat/openai", new CompatRouter(Map.of("/compat/openai", r -> {
                    throw new IllegalStateException("bug");
                })));
        Response thrown = answer(
                throwing, request("/anthropic/v1/messages", MultiMap.caseInsensitiveMultiMap(), MESSAGES), new Host());
        assertEquals(500, thrown.statusCode());
        assertEquals("Internal Server Error", read(thrown));

        Plugin failing = new ModelProviderPlugin(
                vertx, "/compat/openai", new CompatRouter(Map.of("/compat/openai", r -> Future.failedFuture("bug"))));
        Response failed = answer(
                failing, request("/anthropic/v1/messages", MultiMap.caseInsensitiveMultiMap(), MESSAGES), new Host());
        assertEquals(500, failed.statusCode());
        assertEquals("Internal Server Error", read(failed));
    }

    @Test
    void pausedReaderGetsNothingUntilItResumes() throws Exception {
        List<String> events = List.of("data: 1\n\n", "data: 2\n\n", "data: 3\n\n", "data: 4\n\n");
        mock.on("/stream/", r -> {
            HttpServerResponse response = r.response().setChunked(true).putHeader("Content-Type", "text/event-stream");
            writeSpaced(response, events, 0);
        });
        Response answer =
                answer(plugin(Map.of(), settings("adapter", "openai")), streamed("/stream/v1/messages"), new Host());
        List<String> received = new CopyOnWriteArrayList<>();
        CompletableFuture<Void> ended = new CompletableFuture<>();
        ReadStream<Buffer> body = answer.body();
        onContext(() -> call(() -> body.exceptionHandler(ended::completeExceptionally)
                .endHandler(emitted(v -> ended.complete(null)))
                .handler(emitted(chunk -> {
                    received.add(chunk.toString());
                    body.pause();
                }))
                .resume()));
        waitFor(() -> !received.isEmpty());
        Thread.sleep(300); // the upstream has sent everything by now
        assertEquals(1, received.size(), "nothing while paused");

        onContext(() -> call(() ->
                body.handler(emitted(chunk -> received.add(chunk.toString()))).resume()));
        ended.get(10, TimeUnit.SECONDS);
        assertEquals(String.join("", events), String.join("", received));
    }

    @Test
    void fetchGivesOneChunkPerCall() throws Exception {
        List<String> events = List.of("data: 1\n\n", "data: 2\n\n", "data: 3\n\n");
        mock.on(
                "/stream/",
                r -> writeSpaced(
                        r.response().setChunked(true).putHeader("Content-Type", "text/event-stream"), events, 0));
        Response answer =
                answer(plugin(Map.of(), settings("adapter", "openai")), streamed("/stream/v1/messages"), new Host());
        List<String> received = new CopyOnWriteArrayList<>();
        CompletableFuture<Void> ended = new CompletableFuture<>();
        ReadStream<Buffer> body = answer.body();
        onContext(() -> call(() -> body.pause()
                .endHandler(emitted(v -> ended.complete(null)))
                .handler(emitted(chunk -> received.add(chunk.toString())))));
        Thread.sleep(300);
        assertEquals(List.of(), received, "nothing before the first fetch");
        for (int i = 1; i <= events.size(); i++) {
            onContext(() -> call(() -> body.fetch(1)));
            int expected = i;
            waitFor(() -> received.size() == expected);
        }
        onContext(() -> call(() -> body.fetch(1)));
        ended.get(10, TimeUnit.SECONDS);
        assertEquals(String.join("", events), String.join("", received));
    }

    @Test
    void fullQueueHoldsTheWriterBackUntilDrained() throws Exception {
        AnswerBody body = new AnswerBody(context);
        CompletableFuture<Boolean> fullAfterSixteen = new CompletableFuture<>();
        CompletableFuture<Void> drained = new CompletableFuture<>();
        List<String> received = new CopyOnWriteArrayList<>();
        onContext(() -> {
            for (int i = 0; i < 16; i++) {
                assertFalse(body.sink().writeQueueFull(), "below the mark at " + i);
                body.sink().write(Buffer.buffer(String.valueOf(i)));
            }
            fullAfterSixteen.complete(body.sink().writeQueueFull());
            body.sink().drainHandler(v -> drained.complete(null));
        });
        assertTrue(fullAfterSixteen.get(10, TimeUnit.SECONDS), "full at 16 queued chunks, with no reader yet");
        Thread.sleep(100);
        assertFalse(drained.isDone(), "no drain while nothing is read");

        onContext(() -> call(() ->
                body.handler(emitted(chunk -> received.add(chunk.toString()))).resume()));
        drained.get(10, TimeUnit.SECONDS);
        assertEquals(16, received.size());
    }

    @Test
    void brokenUpstreamFailsTheBody() throws Exception {
        mock.on("/stream/", r -> {
            r.response()
                    .setChunked(true)
                    .putHeader("Content-Type", "text/event-stream")
                    .write("data: 1\n\n");
            vertx.setTimer(100, t -> r.connection().close());
        });
        Response answer =
                answer(plugin(Map.of(), settings("adapter", "openai")), streamed("/stream/v1/messages"), new Host());
        List<String> received = new CopyOnWriteArrayList<>();
        CompletableFuture<Void> ended = new CompletableFuture<>();
        CompletableFuture<Throwable> failed = new CompletableFuture<>();
        ReadStream<Buffer> body = answer.body();
        onContext(() -> call(() -> body.exceptionHandler(emitted(failed::complete))
                .endHandler(emitted(v -> ended.complete(null)))
                .handler(emitted(chunk -> received.add(chunk.toString())))
                .resume()));
        failed.get(10, TimeUnit.SECONDS);
        assertEquals(List.of("data: 1\n\n"), received);
        assertFalse(ended.isDone(), "a broken stream does not end");
    }

    @Test
    void clientGoneBeforeTheAnswerClosesTheUpstreamConnection() throws Exception {
        CompletableFuture<Void> closed = new CompletableFuture<>();
        mock.on("/hold/", r -> r.connection().closeHandler(v -> closed.complete(null)));
        Host host = new Host();
        CompletableFuture<Boolean> failed = new CompletableFuture<>();
        Plugin plugin = plugin(Map.of(), settings("adapter", "openai"));
        onContext(() ->
                plugin.handle(streamed("/hold/v1/messages"), host).onComplete(ar -> failed.complete(ar.failed())));
        waitFor(() -> !mock.seen("/hold/").isEmpty());
        onContext(host.cancelled::complete);
        closed.get(10, TimeUnit.SECONDS);
        assertTrue(
                failed.get(10, TimeUnit.SECONDS),
                "a failed answer ends the request quietly; an answer, a 500 for one, would count against the upstream");
    }

    @Test
    void answerCancelledWhileStreamedClosesTheUpstreamConnection() throws Exception {
        CompletableFuture<Void> closed = new CompletableFuture<>();
        mock.on("/stream/", r -> {
            r.connection().closeHandler(v -> closed.complete(null));
            r.response()
                    .setChunked(true)
                    .putHeader("Content-Type", "text/event-stream")
                    .write("data: 1\n\n");
        });
        Response answer =
                answer(plugin(Map.of(), settings("adapter", "openai")), streamed("/stream/v1/messages"), new Host());
        List<String> received = new CopyOnWriteArrayList<>();
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        onContext(() -> call(() -> answer.body()
                .exceptionHandler(emitted(failures::add))
                .handler(emitted(chunk -> received.add(chunk.toString())))
                .resume()));
        waitFor(() -> !received.isEmpty());
        assertFalse(closed.isDone());
        onContext(() -> call(answer::cancel));
        closed.get(10, TimeUnit.SECONDS);
        Thread.sleep(100);
        assertEquals(List.of(), failures, "nothing emitted once cancelled");
    }

    @Test
    void closeClosesKeptAliveUpstreamConnections() throws Exception {
        CompletableFuture<Void> closed = new CompletableFuture<>();
        mock.on("/kept/", r -> {
            r.connection().closeHandler(v -> closed.complete(null));
            r.response().putHeader("Content-Type", "application/json").end("{\"id\":\"msg_1\"}");
        });
        Plugin plugin = plugin(Map.of(), settings("adapter", "openai"));
        MultiMap headers = MultiMap.caseInsensitiveMultiMap()
                .add("X-Upstream-Endpoint", mock.url() + "/kept/v1/messages")
                .add("X-Upstream-Key", "k");
        assertEquals(
                "{\"id\":\"msg_1\"}",
                read(answer(plugin, request("/anthropic/v1/messages", headers, MESSAGES), new Host())));
        Thread.sleep(100);
        assertFalse(closed.isDone(), "kept alive");

        plugin.close();
        closed.get(10, TimeUnit.SECONDS);
    }

    @Test
    void serviceLoaderFindsTheIds() {
        List<String> ids = ServiceLoader.load(PluginProvider.class).stream()
                .map(p -> p.get().type())
                .toList();
        assertEquals(List.of("model-provider", "translator"), ids);
    }

    private Plugin plugin(Map<String, String> environment, ObjectNode settings) {
        ModelProviderPluginProvider provider = new ModelProviderPluginProvider(environment);
        assertEquals(List.of(), provider.validate(settings));
        Plugin plugin = provider.create(settings, vertx);
        plugins.add(plugin);
        return plugin;
    }

    private Request streamed(String endpointPath) {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap()
                .add("X-Upstream-Endpoint", mock.url() + endpointPath)
                .add("X-Upstream-Key", "k");
        return request("/anthropic/v1/messages", headers, STREAMED);
    }

    private static Request request(String uri, MultiMap headers, String body) {
        return new Request(HttpMethod.POST, uri, headers, Buffer.buffer(body), null, null);
    }

    /** On the request's context, as EmbeddedProxyRequest does. */
    private Response answer(Plugin plugin, Request request, HostDelegate host) throws Exception {
        CompletableFuture<Response> answer = new CompletableFuture<>();
        onContext(() -> plugin.handle(request, host).onComplete(ar -> {
            if (ar.succeeded()) {
                answer.complete(ar.result());
            } else {
                answer.completeExceptionally(ar.cause());
            }
        }));
        return answer.get(10, TimeUnit.SECONDS);
    }

    /** The whole body, read on the request's context, as EmbeddedProxyResponse.body() does. */
    private String read(Response answer) throws Exception {
        CompletableFuture<String> read = new CompletableFuture<>();
        Buffer content = Buffer.buffer();
        ReadStream<Buffer> body = answer.body();
        onContext(() -> call(() -> body.exceptionHandler(emitted(read::completeExceptionally))
                .endHandler(emitted(v -> read.complete(content.toString())))
                .handler(emitted(content::appendBuffer))
                .resume()));
        return read.get(10, TimeUnit.SECONDS);
    }

    /** The headers a client sees, less the framing each transport writes itself. */
    private static String withoutFraming(MultiMap headers) {
        return headers.entries().stream()
                .filter(h -> !List.of("content-length", "transfer-encoding")
                        .contains(h.getKey().toLowerCase(Locale.ROOT)))
                .map(h -> h.getKey().toLowerCase(Locale.ROOT) + ": " + h.getValue())
                .sorted()
                .toList()
                .toString();
    }

    private void onContext(Runnable action) {
        context.runOnContext(v -> action.run());
    }

    private void call(Runnable coreCall) {
        inCall = true;
        try {
            coreCall.run();
        } finally {
            inCall = false;
        }
    }

    private <T> Handler<T> emitted(Handler<T> handler) {
        return value -> {
            if (inCall) {
                emittedInCall.add(String.valueOf(value));
            }
            handler.handle(value);
        };
    }

    private void writeSpaced(HttpServerResponse response, List<String> events, int next) {
        if (next == events.size()) {
            response.end();
            return;
        }
        response.write(events.get(next));
        vertx.setTimer(30, t -> writeSpaced(response, events, next + 1));
    }

    private static void waitFor(Supplier<Boolean> condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.get()) {
            assertTrue(System.nanoTime() < deadline, "timed out");
            Thread.sleep(10);
        }
    }

    private static PluginProvider.Problem single(List<PluginProvider.Problem> problems) {
        assertEquals(1, problems.size(), problems.toString());
        return problems.getFirst();
    }

    private static ObjectNode settings(String... pairs) {
        ObjectNode settings = JsonNodeFactory.instance.objectNode();
        for (int i = 0; i < pairs.length; i += 2) {
            settings.put(pairs[i], pairs[i + 1]);
        }
        return settings;
    }

    private static final class Host implements HostDelegate {

        private final Promise<Void> cancelled = Promise.promise();

        @Override
        public Future<Response> call(Call call) {
            return Future.failedFuture("no calls");
        }

        @Override
        public Future<Void> cancelled() {
            return cancelled.future();
        }
    }
}
