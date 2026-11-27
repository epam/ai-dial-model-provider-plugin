package com.epam.aidial.provider.translation;

import com.epam.aidial.provider.ModelProviders;
import com.epam.aidial.provider.api.AdapterRequest;
import com.epam.aidial.provider.api.Handler;
import com.epam.aidial.provider.core.CompatRouter;
import com.epam.aidial.provider.core.Config;
import com.epam.aidial.provider.core.DialApi;
import com.epam.aidial.provider.credentials.AwsCredentials;
import com.epam.aidial.provider.credentials.AzureAd;
import com.epam.aidial.provider.credentials.AzureTokens;
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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.TestFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

/**
 * Runs the cases of the suite {@code translate}: the Anthropic Messages API served for a Responses API upstream, in
 * the two ways the service wires it: on the openai mount ({@link ModelProviders#openaiMount}, the cases of
 * {@code translate/adapter}), and as a service of DIAL Core ({@code /openaiResponses}, {@link
 * MessagesOverResponses#withCoreUpstream}, those of {@code translate/service}, which call DIAL Core back).
 *
 * <p>The stubs: {@code now_seconds}, the clock (the cache expiry); {@code azure_ad_token}, the Azure AD token every
 * call gets (without it, getting one fails with {@code azure_ad_error}, or "no Azure AD in these cases");
 * {@code aws_access_key_id}, the access key of the default credential chain (AKID; its secret is "s").
 */
class MessagesToResponsesCasesTest {

    private static CasesServer server;
    private static HttpTestRunner runner;

    @BeforeAll
    static void start() throws Exception {
        server = new CasesServer();
        runner = server.runner(MessagesToResponsesCasesTest::prepare);
    }

    @AfterAll
    static void stop() throws Exception {
        server.close();
    }

    @TestFactory
    Stream<DynamicContainer> cases() {
        return CasesServer.containers(SupportedRoutes.suites(Runner.TRANSLATE), suite -> runner);
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
        DialApi dial = new DialApi(vertx);
        AzureAd azure = new AzureAd(
                (scope, aborted) -> token != null
                        ? Future.succeededFuture(token)
                        : Future.failedFuture(new AzureTokens.AzureAdException(new IllegalStateException(tokenError))),
                "scope");
        AwsCredentials aws = new AwsCredentials(
                vertx, config, () -> StaticCredentialsProvider.create(AwsBasicCredentials.create(keyId, "s")));
        UpstreamClient dialCore = server.own(
                new UpstreamClient(vertx, config.proxies(), Limits.uncapped(Duration.ofSeconds(30))),
                UpstreamClient::close);
        Handler core = new DialCoreHttp(dialCore, config.dialUrl());
        Handler service = new CompatRouter(
                Map.of(ModelProviders.TRANSLATOR_PREFIX, MessagesOverResponses.withCoreUpstream(CoreHandler::handle)));
        Handler openai = ModelProviders.openaiMount(
                vertx,
                config,
                "/compat/openai",
                client,
                azure,
                aborted -> Future.failedFuture("no Google token in these cases"),
                aws,
                dial,
                Clock.fixed(Instant.ofEpochSecond(now), ZoneOffset.UTC));
        return request -> request.path().startsWith(ModelProviders.TRANSLATOR_PREFIX + "/")
                ? service.handle(new AdapterRequest(
                        request.method(),
                        request.path(),
                        request.query(),
                        request.headers(),
                        request.body(),
                        request.aborted(),
                        core))
                : openai.handle(request);
    }
}
