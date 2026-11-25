package com.epam.aidial.provider.plugin;

import com.epam.aidial.core.plugin.Call;
import com.epam.aidial.core.plugin.HostDelegate;
import com.epam.aidial.core.plugin.Plugin;
import com.epam.aidial.core.plugin.Request;
import com.epam.aidial.core.plugin.Response;
import com.epam.aidial.provider.api.AdapterRequest;
import com.epam.aidial.provider.api.AdapterResponse;
import com.epam.aidial.provider.api.Body;
import com.epam.aidial.provider.api.Handler;
import com.epam.aidial.provider.core.Buffers;
import com.epam.aidial.provider.testing.http.CasesServer;
import com.epam.aidial.provider.testing.http.HttpTestRunner;
import com.epam.aidial.provider.testing.http.SupportedRoutes;
import com.epam.aidial.provider.testing.http.SupportedRoutes.Runner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.streams.ReadStream;
import java.io.IOException;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.TestFactory;

/**
 * Runs the cases of the suite {@code translate} that are of the mode {@code plugin}: the translator as a plugin entry
 * ({@code "translator": "openaiResponses"}), as DIAL Core drives a plugin. This class plays DIAL Core: it passes the
 * request to the plugin as DIAL Core does (the exact path of the interface, the headers, the body), and its host calls
 * the mock DIAL Core of the case (its {@code server.env.DIAL_URL}) over HTTP, with the key of the request, as DIAL Core
 * serves a call with it. Everything else is the plugin's own.
 */
class PluginCasesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static CasesServer server;
    private static HttpTestRunner runner;

    @BeforeAll
    static void start() throws Exception {
        server = new CasesServer();
        runner = server.runner(PluginCasesTest::prepare);
    }

    @AfterAll
    static void stop() throws Exception {
        server.close();
    }

    @TestFactory
    Stream<DynamicContainer> cases() {
        return CasesServer.containers(SupportedRoutes.suites(Runner.PLUGIN), suite -> runner);
    }

    private static Handler prepare(JsonNode setup) {
        Vertx vertx = server.vertx();
        Map<String, String> env = CasesServer.env(setup);
        ObjectNode entry = JsonNodeFactory.instance.objectNode().put("translator", "openaiResponses");
        Plugin plugin = new ModelProviderPluginProvider(env).create(entry, vertx);
        server.own(plugin, p -> {
            p.close();
            return Future.succeededFuture();
        });
        HttpClient client = server.own(vertx.createHttpClient(), HttpClient::close);
        String coreUrl = env.getOrDefault("DIAL_URL", "http://127.0.0.1:1");
        return request -> serve(plugin, new Host(client, coreUrl, request));
    }

    private static Future<AdapterResponse> serve(Plugin plugin, Host host) {
        AdapterRequest request = host.request;
        String uri = request.query() == null ? request.path() : request.path() + "?" + request.query();
        // DIAL Core parses the body for the plugin; a body that is no JSON object is its own failure to answer, and the
        // plugin, which reads the bytes, then gets an empty tree
        ObjectNode tree = JsonNodeFactory.instance.objectNode();
        try {
            if (MAPPER.readTree(Buffers.array(request.body())) instanceof ObjectNode object) {
                tree = object;
            }
        } catch (IOException e) {
            // an empty tree
        }
        host.cancelledOnAbort(request.aborted());
        return plugin.handle(new Request(request.method(), uri, request.headers(), request.body(), tree, null), host)
                .map(response -> new AdapterResponse(
                        response.statusCode(),
                        response.headers(),
                        new Body.Stream(response.body().pipe())));
    }

    private static final class Host implements HostDelegate {

        private final HttpClient client;
        private final String coreUrl;
        final AdapterRequest request;
        private final Promise<Void> cancelled = Promise.promise();

        Host(HttpClient client, String coreUrl, AdapterRequest request) {
            this.client = client;
            this.coreUrl = coreUrl;
            this.request = request;
        }

        void cancelledOnAbort(Future<Void> aborted) {
            aborted.onComplete(v -> cancelled.tryComplete());
        }

        @Override
        public Future<Response> call(Call call) {
            MultiMap headers = MultiMap.caseInsensitiveMultiMap().addAll(call.headers());
            String key = request.headers().get("api-key");
            if (key != null) {
                headers.set("Api-Key", key);
            }
            RequestOptions options = new RequestOptions()
                    .setMethod(HttpMethod.POST)
                    .setAbsoluteURI(coreUrl + call.uri())
                    .setHeaders(headers);
            return client.request(options)
                    .compose(outgoing -> outgoing.send(Buffer.buffer(call.body().toString()))
                            .map(incoming -> answer(outgoing, incoming)));
        }

        private static Response answer(HttpClientRequest outgoing, HttpClientResponse incoming) {
            incoming.pause();
            return new Response() {
                @Override
                public int statusCode() {
                    return incoming.statusCode();
                }

                @Override
                public MultiMap headers() {
                    return incoming.headers();
                }

                @Override
                public ReadStream<Buffer> body() {
                    return incoming;
                }

                @Override
                public void cancel() {
                    outgoing.reset();
                }
            };
        }

        @Override
        public Future<Void> cancelled() {
            return cancelled.future();
        }
    }
}
