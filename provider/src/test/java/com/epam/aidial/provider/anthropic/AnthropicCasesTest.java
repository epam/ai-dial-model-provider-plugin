package com.epam.aidial.provider.anthropic;

import com.epam.aidial.provider.api.Handler;
import com.epam.aidial.provider.core.Config;
import com.epam.aidial.provider.core.DialApi;
import com.epam.aidial.provider.core.Routes;
import com.epam.aidial.provider.credentials.AwsCredentials;
import com.epam.aidial.provider.credentials.AzureAd;
import com.epam.aidial.provider.credentials.AzureTokens;
import com.epam.aidial.provider.credentials.SessionTags;
import com.epam.aidial.provider.testing.http.CasesServer;
import com.epam.aidial.provider.testing.http.HttpTestRunner;
import com.epam.aidial.provider.testing.http.SupportedRoutes;
import com.epam.aidial.provider.testing.http.SupportedRoutes.Runner;
import com.epam.aidial.provider.upstream.Limits;
import com.epam.aidial.provider.upstream.UpstreamClient;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicContainer;
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

    private static CasesServer server;
    private static Map<String, HttpTestRunner> runners;

    @BeforeAll
    static void start() throws Exception {
        server = new CasesServer();
        runners = new HashMap<>();
        for (String adapter : List.of("openai", "vertexai", "bedrock")) {
            runners.put(adapter, server.runner(setup -> prepare(adapter, setup)));
        }
    }

    @AfterAll
    static void stop() throws Exception {
        server.close();
    }

    /** Every suite on the classpath, so that a new one is never skipped; an unknown adapter prefix fails its cases. */
    @TestFactory
    Stream<DynamicContainer> cases() {
        return CasesServer.containers(SupportedRoutes.suites(Runner.ANTHROPIC), AnthropicCasesTest::runner);
    }

    private static HttpTestRunner runner(String suite) {
        HttpTestRunner runner = runners.get(suite.substring(0, Math.max(suite.indexOf('-'), 0)));
        if (runner == null) {
            throw new IllegalStateException("the suite " + suite + " is named for no adapter of " + runners.keySet());
        }
        return runner;
    }

    private static Handler prepare(String adapter, JsonNode setup) {
        Vertx vertx = server.vertx();
        JsonNode stubs = setup.path("stubs");
        long now = stubs.path("now_seconds").asLong();
        Config config = Config.from(CasesServer.env(setup));
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
        UpstreamClient client =
                server.own(new UpstreamClient(vertx, config.proxies(), connections), UpstreamClient::close);
        return Routes.of(new AnthropicRoutes(
                "/compat/" + adapter,
                resolver,
                client,
                new AzureAd(
                        (scope, aborted) -> azureAdToken(stubs),
                        stubs.path("azure_scope").asText()),
                aborted ->
                        Future.succeededFuture(stubs.path("google_access_token").asText()),
                Clock.fixed(Instant.ofEpochSecond(now), ZoneOffset.UTC)));
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
