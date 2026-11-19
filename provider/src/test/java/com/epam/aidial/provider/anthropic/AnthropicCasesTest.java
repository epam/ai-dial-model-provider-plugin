package com.epam.aidial.provider.anthropic;

import static com.epam.aidial.provider.testing.MockUpstream.await;

import com.epam.aidial.provider.api.ModelProvider;
import com.epam.aidial.provider.core.Config;
import com.epam.aidial.provider.core.DialApi;
import com.epam.aidial.provider.core.HttpServerShim;
import com.epam.aidial.provider.core.Routes;
import com.epam.aidial.provider.credentials.AwsCredentials;
import com.epam.aidial.provider.credentials.AzureAd;
import com.epam.aidial.provider.credentials.AzureTokens;
import com.epam.aidial.provider.credentials.SessionTags;
import com.epam.aidial.provider.testing.http.HttpTestCase;
import com.epam.aidial.provider.testing.http.HttpTestRunner;
import com.epam.aidial.provider.testing.http.SupportedRoutes;
import com.epam.aidial.provider.upstream.Limits;
import com.epam.aidial.provider.upstream.UpstreamClient;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

/**
 * The stubs of a case's {@code server} member: {@code now_seconds}, the clock (the cache expiry);
 * {@code google_access_token}, the Google token; {@code azure_ad_token}, the Azure AD token ({@code azure_ad_error},
 * its failure, wins; neither, a failure too); {@code aws_access_key_id}, the access key of the default credential
 * chain ({@code AKID} by default).
 *
 * <p>Every case runs as written but those of the Responses API ({@code OpenAiResponsesCasesTest}); the cases of the
 * routes the service does not serve (suites <code>*-removed</code>) expect the {@code 404}, no call sent.
 */
class AnthropicCasesTest {

    private static Vertx vertx;
    private static HttpServer server;
    private static Map<String, HttpTestRunner> runners;
    private static volatile ModelProvider routes;
    /** The case's upstream clients, closed before the next case's: their idle connections would add up on the mocks. */
    private static final List<UpstreamClient> CLIENTS = new ArrayList<>();

    @BeforeAll
    static void start() throws Exception {
        vertx = Vertx.vertx();
        server = await(vertx.createHttpServer()
                .requestHandler(new HttpServerShim(request -> routes.handle(request)))
                .listen(0, "127.0.0.1"));
        runners = new HashMap<>();
        for (String adapter : List.of("openai", "vertexai", "bedrock")) {
            runners.put(adapter, new HttpTestRunner(setup -> prepare(adapter, setup)));
        }
    }

    @AfterAll
    static void stop() throws Exception {
        closeClients();
        runners.values().forEach(HttpTestRunner::close);
        await(vertx.close());
    }

    /** Every suite on the classpath, so that a new one is never skipped; an unknown adapter prefix fails its cases. */
    @TestFactory
    Stream<DynamicContainer> cases() {
        List<HttpTestCase> own = HttpTestCase.load("http-tests").stream()
                .filter(c -> !SupportedRoutes.isResponses(c))
                .toList();
        return SupportedRoutes.suites(own).entrySet().stream()
                .map(suite -> DynamicContainer.dynamicContainer(
                        suite.getKey(),
                        suite.getValue().stream()
                                .map(c -> DynamicTest.dynamicTest(
                                        c.name(), c.file().toUri(), () -> runner(suite.getKey())
                                                .run(c)))));
    }

    private static HttpTestRunner runner(String suite) {
        HttpTestRunner runner = runners.get(suite.substring(0, Math.max(suite.indexOf('-'), 0)));
        if (runner == null) {
            throw new IllegalStateException("the suite " + suite + " is named for no adapter of " + runners.keySet());
        }
        return runner;
    }

    private static URI prepare(String adapter, JsonNode setup) throws Exception {
        closeClients();
        Map<String, String> env = new HashMap<>();
        setup.path("env")
                .properties()
                .forEach(e -> env.put(e.getKey(), e.getValue().asText()));
        JsonNode stubs = setup.path("stubs");
        long now = stubs.path("now_seconds").asLong();
        Config config = Config.from(env);
        AwsCredentials aws = new AwsCredentials(
                vertx,
                config,
                () -> StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        stubs.path("aws_access_key_id").asText("AKID"), "s")));
        Targets.Resolver resolver;
        Limits connections;
        switch (adapter) {
            case "openai" -> {
                resolver = Targets.openai();
                connections = config.openai();
            }
            case "vertexai" -> {
                resolver = Targets.vertexai(config);
                connections = config.vertexai();
            }
            default -> {
                resolver = Targets.bedrock(config, aws, SessionTags.of(config, DialApi.from(vertx, config)));
                connections = config.bedrock();
            }
        }
        UpstreamClient client = new UpstreamClient(vertx, config.proxies(), connections);
        CLIENTS.add(client);
        routes = Routes.of(new AnthropicRoutes(
                "/compat/" + adapter,
                resolver,
                client,
                new AzureAd(
                        (scope, aborted) -> azureAdToken(stubs),
                        stubs.path("azure_scope").asText()),
                aborted ->
                        Future.succeededFuture(stubs.path("google_access_token").asText()),
                Clock.fixed(Instant.ofEpochSecond(now), ZoneOffset.UTC)));
        return URI.create("http://127.0.0.1:" + server.actualPort());
    }

    private static void closeClients() throws Exception {
        for (UpstreamClient c : CLIENTS) {
            await(c.close());
        }
        CLIENTS.clear();
    }

    private static Future<String> azureAdToken(JsonNode stubs) {
        if (stubs.has("azure_ad_error")) {
            return Future.failedFuture(new AzureTokens.AzureAdException(
                    new IllegalStateException(stubs.get("azure_ad_error").asText())));
        }
        return stubs.has("azure_ad_token")
                ? Future.succeededFuture(stubs.get("azure_ad_token").asText())
                : Future.failedFuture(new IllegalStateException("no Azure AD token stub"));
    }
}
