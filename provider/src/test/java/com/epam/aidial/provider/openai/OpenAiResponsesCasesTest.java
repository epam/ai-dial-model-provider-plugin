package com.epam.aidial.provider.openai;

import static com.epam.aidial.provider.testing.MockUpstream.await;

import com.epam.aidial.provider.api.ModelProvider;
import com.epam.aidial.provider.core.Config;
import com.epam.aidial.provider.core.DialApi;
import com.epam.aidial.provider.core.HttpServerShim;
import com.epam.aidial.provider.core.Routes;
import com.epam.aidial.provider.credentials.AwsCredentials;
import com.epam.aidial.provider.credentials.AzureAd;
import com.epam.aidial.provider.credentials.AzureTokenSource;
import com.epam.aidial.provider.credentials.AzureTokens;
import com.epam.aidial.provider.testing.http.HttpTestCase;
import com.epam.aidial.provider.testing.http.HttpTestRunner;
import com.epam.aidial.provider.testing.http.SupportedRoutes;
import com.epam.aidial.provider.upstream.UpstreamClient;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
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
 * Runs the cases whose request is to a Responses route (suites <code>openai-responses</code> and
 * <code>openai-chat</code>'s <code>routes/</code>); the cases of the other APIs are not this class's.
 *
 * <p>The stubs: {@code now_seconds}, the clock (the cache expiry); {@code azure_ad_token}, the Azure AD token every
 * call gets (without it, getting one fails with {@code azure_ad_error}, or "no Azure AD in these cases");
 * {@code aws_access_key_id}, the access key of the default credential chain (AKID; its secret is "s").
 */
class OpenAiResponsesCasesTest {

    private static Vertx vertx;
    private static HttpServer server;
    private static HttpTestRunner runner;
    private static volatile ModelProvider routes;
    // The case's clients (the Responses one, DIAL Core's), closed before the next case's: their idle connections would
    // add up on the mocks.
    private static UpstreamClient client;
    private static DialApi dial;

    @BeforeAll
    static void start() throws Exception {
        vertx = Vertx.vertx();
        server = await(vertx.createHttpServer()
                .requestHandler(new HttpServerShim(request -> routes.handle(request)))
                .listen(0, "127.0.0.1"));
        runner = new HttpTestRunner(OpenAiResponsesCasesTest::prepare);
    }

    @AfterAll
    static void stop() throws Exception {
        closeClients();
        runner.close();
        await(vertx.close());
    }

    @TestFactory
    Stream<DynamicContainer> cases() {
        var own = HttpTestCase.load("http-tests").stream()
                .filter(SupportedRoutes::isResponses)
                .toList();
        return SupportedRoutes.suites(own).entrySet().stream()
                .map(suite -> DynamicContainer.dynamicContainer(
                        suite.getKey(),
                        suite.getValue().stream()
                                .map(c -> DynamicTest.dynamicTest(
                                        c.name(), c.file().toUri(), () -> runner.run(c)))));
    }

    private static URI prepare(JsonNode setup) throws Exception {
        closeClients();
        Map<String, String> env = new HashMap<>();
        setup.path("env")
                .properties()
                .forEach(e -> env.put(e.getKey(), e.getValue().asText()));
        JsonNode stubs = setup.path("stubs");
        long now = stubs.path("now_seconds").asLong();
        String token = stubs.path("azure_ad_token").asText(null);
        String tokenError = stubs.path("azure_ad_error").asText("no Azure AD in these cases");
        String keyId = stubs.path("aws_access_key_id").asText("AKID");
        Config config = Config.from(env);
        client = new UpstreamClient(vertx, config.proxies(), config.openai());
        dial = DialApi.from(vertx, config);
        AzureTokenSource azure = (scope, aborted) -> token != null
                ? Future.succeededFuture(token)
                : Future.failedFuture(new AzureTokens.AzureAdException(new IllegalStateException(tokenError)));
        AwsCredentials aws = new AwsCredentials(
                vertx, config, () -> StaticCredentialsProvider.create(AwsBasicCredentials.create(keyId, "s")));
        routes = Routes.of(new ResponsesRoutes(
                vertx,
                config,
                "/compat/openai",
                Targets.openai(config),
                client,
                new AzureAd(azure, "scope"),
                aws,
                dial,
                Clock.fixed(Instant.ofEpochSecond(now), ZoneOffset.UTC)));
        return URI.create("http://127.0.0.1:" + server.actualPort());
    }

    private static void closeClients() throws Exception {
        if (client != null) {
            await(client.close());
            client = null;
        }
        if (dial != null) {
            await(dial.close());
            dial = null;
        }
    }
}
