package com.epam.aidial.provider.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.epam.aidial.provider.api.AdapterRequest;
import com.epam.aidial.provider.api.AdapterResponse;
import com.epam.aidial.provider.api.Body;
import com.epam.aidial.provider.core.Config;
import com.epam.aidial.provider.credentials.Authorizer;
import com.epam.aidial.provider.credentials.AwsCredentials;
import com.epam.aidial.provider.credentials.AzureAd;
import com.epam.aidial.provider.testing.Requests;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

class TargetsTest {

    private static final String MANTLE = "https://bedrock-mantle.us-east-1.api.aws/openai/v1/responses";
    private static final String RUNTIME = "https://bedrock-runtime.eu-west-1.amazonaws.com/openai/v1/responses";

    private Vertx vertx;

    @BeforeEach
    void start() {
        vertx = Vertx.vertx();
    }

    @AfterEach
    void stop() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private record Upstream(OpenAiTargets.Resolver resolver, UpstreamAuth auth) {}

    private Upstream upstream(String... settings) {
        Map<String, String> s = new HashMap<>();
        for (int i = 0; i < settings.length; i += 2) {
            s.put(settings[i], settings[i + 1]);
        }
        Config config = Config.from(s);
        AwsCredentials aws = new AwsCredentials(
                vertx, config, () -> StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIDCHAIN", "s")));
        return new Upstream(
                OpenAiTargets.openai(config),
                new UpstreamAuth(
                        config,
                        new AzureAd((scope, aborted) -> Future.succeededFuture("aad"), "scope"),
                        aws,
                        null,
                        Clock.systemUTC()));
    }

    private static AdapterRequest request(String query, String... headers) {
        MultiMap h = MultiMap.caseInsensitiveMultiMap();
        for (int i = 0; i < headers.length; i += 2) {
            h.add(headers[i], headers[i + 1]);
        }
        return Requests.of(
                HttpMethod.POST,
                "/openai/v1/responses",
                query,
                h,
                Buffer.buffer(),
                Promise.<Void>promise().future());
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private record Resolved(Target target, MultiMap headers) {}

    private static Resolved resolve(Upstream upstream, AdapterRequest request) throws Exception {
        Target t = upstream.resolver().resolve(request);
        Authorizer authorizer = await(upstream.auth().forRequest(t, request.headers(), null, request.aborted()));
        MultiMap headers = OpenAiHeaders.toUpstream(t, Endpoint.CREATE);
        await(authorizer.authorize(HttpMethod.POST, t.baseUrl(), headers, null));
        return new Resolved(t, headers);
    }

    private static String answerOf(Throwable e) {
        AdapterResponse response = new OpenAiErrors.Failures(0)
                .answer(e, "/p")
                .toCompletionStage()
                .toCompletableFuture()
                .join();
        return response.status() + " "
                + new String(((Body.Bytes) response.body()).buffer().getBytes(), StandardCharsets.UTF_8);
    }

    private static String refusal(Upstream upstream, AdapterRequest request) {
        Throwable e = assertThrows(Exception.class, () -> resolve(upstream, request));
        return answerOf(e instanceof ExecutionException ? e.getCause() : e);
    }

    @Test
    void v1KeyIsBearerToken() throws Exception {
        Resolved t = resolve(
                upstream(),
                request(null, "X-Upstream-Endpoint", "https://api.example.com/v1/responses", "X-Upstream-Key", "k"));
        assertEquals("https://api.example.com/v1/responses", t.target().baseUrl());
        assertFalse(t.target().versioned());
        assertEquals("Bearer k", t.headers().get("Authorization"));
    }

    @Test
    void azureDeploymentKeyIsAnApiKeyAndTheDeploymentIsLeftOut() throws Exception {
        Resolved t = resolve(
                upstream(),
                request(
                        "api-version=a&api-version=b",
                        "X-Upstream-Endpoint",
                        "https://r.openai.azure.com/openai/deployments/d/responses",
                        "X-Upstream-Key",
                        "k"));
        assertEquals("https://r.openai.azure.com/openai/responses", t.target().baseUrl());
        assertTrue(t.target().versioned());
        assertEquals("b", t.target().version());
        assertEquals("k", t.headers().get("Api-Key"));
        assertNull(t.headers().get("Authorization"));
    }

    @Test
    void azureWithoutKeyTakesAnAdToken() throws Exception {
        Resolved t = resolve(
                upstream(),
                request("api-version=a", "X-Upstream-Endpoint", "https://r.openai.azure.com/openai/responses"));
        assertEquals("Bearer aad", t.headers().get("Authorization"));
    }

    @Test
    void headersToProxyAreSentExceptAcceptEncoding() throws Exception {
        Resolved t = resolve(
                upstream(),
                request(
                        null,
                        "X-Upstream-Endpoint",
                        "https://api.example.com/v1/responses",
                        "X-Upstream-Key",
                        "k",
                        "X-Upstream-Extra-Data",
                        """
                                {"headers_to_proxy":["x-tenant","Accept-Encoding","x-absent"]}""",
                        "x-tenant",
                        "t1",
                        "Accept-Encoding",
                        "gzip"));
        MultiMap headers = OpenAiHeaders.toUpstream(t.target(), Endpoint.CREATE);
        assertEquals("t1", headers.get("x-tenant"));
        assertNull(headers.get("Accept-Encoding"));
        assertNull(headers.get("x-absent"));
        assertEquals("application/json", headers.get("Content-Type"));
        assertNull(OpenAiHeaders.toUpstream(t.target(), Endpoint.RETRIEVE).get("Content-Type"));
    }

    @Test
    void proxiedAuthorizationWinsOverTheKeyOfTheV1Client() throws Exception {
        Resolved t = resolve(
                upstream(),
                request(
                        null,
                        "X-Upstream-Endpoint",
                        "https://api.example.com/v1/responses",
                        "X-Upstream-Key",
                        "k",
                        "X-Upstream-Extra-Data",
                        "{\"headers_to_proxy\":[\"Authorization\"]}",
                        "Authorization",
                        "Bearer own"));
        assertEquals("Bearer own", t.headers().get("Authorization"));
    }

    @Test
    void runtimeWithStaticKeysSendsBedrockApiKey() throws Exception {
        Resolved t = resolve(
                upstream(),
                request(
                        null,
                        "X-Upstream-Endpoint",
                        RUNTIME,
                        "X-Upstream-Extra-Data",
                        """
                                {"aws_access_key_id":"AKID","aws_secret_access_key":"sec"}"""));
        assertEquals(
                "https://bedrock-runtime.eu-west-1.amazonaws.com/openai/v1/responses",
                t.target().baseUrl());
        assertEquals("eu-west-1", t.target().bedrock().region());
        assertTrue(t.headers().get("Authorization").startsWith("Bearer bedrock-api-key-"));
    }

    @Test
    void mantleWithoutKeysTakesTheBearerOfTheEnvironment() throws Exception {
        Resolved t = resolve(
                upstream("AWS_BEARER_TOKEN_BEDROCK", "env-bearer"), request(null, "X-Upstream-Endpoint", MANTLE));
        assertEquals(
                "https://bedrock-mantle.us-east-1.api.aws/openai/v1/responses",
                t.target().baseUrl());
        assertEquals("us-east-1", t.target().bedrock().region());
        assertEquals("Bearer env-bearer", t.headers().get("Authorization"));
    }

    @Test
    void mantleBaseUrlSettingReplacesTheEndpoint() throws Exception {
        Resolved t = resolve(
                upstream("AWS_BEDROCK_BASE_URL", "http://localhost:9/custom/responses/"),
                request(null, "X-Upstream-Endpoint", MANTLE, "X-Upstream-Key", "k"));
        assertEquals("http://localhost:9/custom/responses", t.target().baseUrl());
    }

    @Test
    void refusalsAreTheAdaptersAnswers() {
        Upstream upstream = upstream();
        assertEquals(
                """
                        502 {"error":{"message":"X-UPSTREAM-ENDPOINT header is missing in the request.","type":"internal_server_error","code":"502"}}""",
                refusal(upstream, request(null)).strip());
    }

    @Test
    void extraDataTheAdapterRefusesIsA502() {
        String notJson = "X-Upstream-Extra-Data header doesn't contain valid JSON";
        String notObject = "X-Upstream-Extra-Data header doesn't contain the expected JSON object";
        Map.ofEntries(
                        Map.entry("{", notJson),
                        Map.entry("{} x", notJson),
                        Map.entry("", notObject),
                        Map.entry("[]", notObject),
                        Map.entry("null", notObject),
                        Map.entry("{\"headers_to_proxy\":1}", notObject),
                        Map.entry("{\"headers_to_proxy\":[\"a\",2]}", notObject),
                        Map.entry("{\"vendor\":\"x\"}", notObject),
                        Map.entry("{\"aws_session_token\":true}", notObject),
                        Map.entry("{\"aws_secret_access_key\":5}", notObject))
                .forEach((raw, message) -> assertEquals(
                        "502 {\"error\":{\"message\":\"" + message
                                + "\",\"type\":\"internal_server_error\",\"code\":\"502\"}}",
                        refusal(
                                        upstream(),
                                        request(
                                                null,
                                                "X-Upstream-Endpoint",
                                                "https://api.example.com/v1/responses",
                                                "X-Upstream-Extra-Data",
                                                raw))
                                .strip(),
                        raw));
    }

    @Test
    void extraDataTakesNullMembersAsAbsent() throws Exception {
        Resolved t = resolve(
                upstream(),
                request(
                        null,
                        "X-Upstream-Endpoint",
                        "https://api.example.com/v1/responses",
                        "X-Upstream-Key",
                        "k",
                        "X-Upstream-Extra-Data",
                        """
                                {"vendor":null,"aws_access_key_id":null,"headers_to_proxy":[],"unknown":1}"""));
        assertNull(t.target().extra().vendor());
        assertEquals("", t.target().extra().aws().accessKeyId(), "a null is the empty string");
    }
}
