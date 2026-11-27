package com.epam.aidial.provider.openai;

import com.epam.aidial.provider.api.Handler;
import com.epam.aidial.provider.core.Config;
import com.epam.aidial.provider.core.DialApi;
import com.epam.aidial.provider.core.Routes;
import com.epam.aidial.provider.credentials.AwsCredentials;
import com.epam.aidial.provider.credentials.AzureAd;
import com.epam.aidial.provider.credentials.AzureTokenSource;
import com.epam.aidial.provider.credentials.AzureTokens;
import com.epam.aidial.provider.testing.http.CasesServer;
import com.epam.aidial.provider.testing.http.HttpTestRunner;
import com.epam.aidial.provider.testing.http.SupportedRoutes;
import com.epam.aidial.provider.testing.http.SupportedRoutes.Runner;
import com.epam.aidial.provider.upstream.UpstreamClient;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicContainer;
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

    private static CasesServer server;
    private static HttpTestRunner runner;

    @BeforeAll
    static void start() throws Exception {
        server = new CasesServer();
        runner = server.runner(OpenAiResponsesCasesTest::prepare);
    }

    @AfterAll
    static void stop() throws Exception {
        server.close();
    }

    @TestFactory
    Stream<DynamicContainer> cases() {
        return CasesServer.containers(SupportedRoutes.suites(Runner.RESPONSES), suite -> runner);
    }

    private static Handler prepare(JsonNode setup) {
        Vertx vertx = server.vertx();
        JsonNode stubs = setup.path("stubs");
        long now = stubs.path("now_seconds").asLong();
        String token = stubs.path("azure_ad_token").asText(null);
        String tokenError = stubs.path("azure_ad_error").asText("no Azure AD in these cases");
        String keyId = stubs.path("aws_access_key_id").asText("AKID");
        Config config = Config.from(CasesServer.env(setup));
        UpstreamClient client =
                server.own(new UpstreamClient(vertx, config.proxies(), config.openai()), UpstreamClient::close);
        DialApi dial = server.own(DialApi.from(vertx, config), DialApi::close);
        AzureTokenSource azure = (scope, aborted) -> token != null
                ? Future.succeededFuture(token)
                : Future.failedFuture(new AzureTokens.AzureAdException(new IllegalStateException(tokenError)));
        AwsCredentials aws = new AwsCredentials(
                vertx, config, () -> StaticCredentialsProvider.create(AwsBasicCredentials.create(keyId, "s")));
        return Routes.of(new ResponsesRoutes(
                vertx,
                config,
                "/compat/openai",
                OpenAiTargets.openai(config),
                client,
                new AzureAd(azure, "scope"),
                aws,
                dial,
                Clock.fixed(Instant.ofEpochSecond(now), ZoneOffset.UTC)));
    }
}
