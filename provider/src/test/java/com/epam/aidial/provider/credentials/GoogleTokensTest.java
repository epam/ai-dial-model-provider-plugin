package com.epam.aidial.provider.credentials;

import static com.epam.aidial.provider.testing.MockUpstream.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.epam.aidial.provider.core.Config;
import com.epam.aidial.provider.json.Json;
import com.epam.aidial.provider.testing.MockUpstream;
import com.epam.aidial.provider.upstream.ClientGoneException;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;

/**
 * google-credentials. google-auth takes https token URLs only: the files name https hosts, which {@link #local}
 * answers with the fakes. The AWS SDK finds the container credentials in the JVM's system properties, so the class
 * runs alone.
 */
@Isolated
class GoogleTokensTest {

    private static final List<String> CONTAINER_PROPERTIES = List.of(
            "aws.containerCredentialsFullUri",
            "aws.containerCredentialsPath",
            "aws.containerAuthorizationToken",
            "aws.containerAuthorizationTokenFile");

    private static final Future<Void> NEVER = Promise.<Void>promise().future();
    private static final String AUDIENCE =
            "//iam.googleapis.com/projects/1/locations/global/workloadIdentityPools/pool/providers/aws";

    private static final String STS_OK =
            """
            {"access_token":"google-tok","expires_in":3600,"token_type":"Bearer","issued_token_type":"urn:ietf:params:oauth:token-type:access_token"}""";

    @TempDir
    Path dir;

    private Vertx vertx;
    private MockUpstream mock;
    private final AtomicInteger stsCalls = new AtomicInteger();
    private volatile int containerStatus = 200;
    private volatile String stsAnswer = STS_OK;

    @BeforeEach
    void start() throws Exception {
        vertx = Vertx.vertx();
        mock = new MockUpstream(vertx)
                .on(
                        "/ecs/creds",
                        r -> r.response()
                                .setStatusCode(containerStatus)
                                .end(
                                        """
                                        {"AccessKeyId":"ASIACONTAINER","SecretAccessKey":"s","Token":"container-session","Expiration":"2099-01-01T00:00:00Z"}"""))
                .on("/sts/token", r -> {
                    stsCalls.incrementAndGet();
                    vertx.setTimer(100, t -> r.response()
                            .putHeader("Content-Type", "application/json")
                            .end(stsAnswer));
                })
                .reply(
                        "/v1/projects/-/serviceAccounts/",
                        200,
                        Map.of("Content-Type", "application/json"),
                        """
                                {"accessToken":"sa-tok","expireTime":"2099-01-01T00:00:00Z"}""");
    }

    @AfterEach
    void stop() throws Exception {
        CONTAINER_PROPERTIES.forEach(System::clearProperty);
        await(vertx.close());
    }

    private GoogleTokens tokens(Map<String, Object> file, Map<String, String> extra) throws Exception {
        Path path = dir.resolve("wif.json");
        Files.writeString(path, Json.encode(file));
        System.setProperty("aws.containerCredentialsFullUri", mock.url() + "/ecs/creds");
        Map<String, String> settings =
                new HashMap<>(Map.of("GOOGLE_APPLICATION_CREDENTIALS", path.toString(), "AWS_REGION", "eu-west-1"));
        settings.putAll(extra);
        return new GoogleTokens(vertx, Config.from(settings), this::local);
    }

    private URL local(URL url) {
        try {
            return URI.create(mock.url() + url.getFile()).toURL();
        } catch (MalformedURLException e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Object> file() {
        Map<String, Object> file = new HashMap<>(Map.of(
                "type",
                "external_account",
                "audience",
                AUDIENCE,
                "subject_token_type",
                "urn:ietf:params:aws:token-type:aws4_request",
                "token_url",
                "https://sts.test/sts/token",
                "credential_source",
                Map.of(
                        "environment_id",
                        "aws1",
                        "regional_cred_verification_url",
                        "https://sts.{region}.amazonaws.com?Action=GetCallerIdentity&Version=2011-06-15")));
        return file;
    }

    @Test
    void workloadIdentityExchange() throws Exception {
        Path tokenFile = dir.resolve("container-token");
        Files.writeString(tokenFile, "container-token");
        System.setProperty("aws.containerAuthorizationTokenFile", tokenFile.toString());
        GoogleTokens tokens = tokens(file(), Map.of());

        assertEquals("google-tok", await(tokens.token(NEVER)));
        assertEquals(
                "container-token", mock.seen("/ecs/creds").getFirst().headers().get("Authorization"));
        MockUpstream.Seen sts = mock.seen("/sts/token").getFirst();
        Map<String, String> form = form(sts.body());
        assertEquals("urn:ietf:params:oauth:grant-type:token-exchange", form.get("grant_type"));
        assertEquals(AUDIENCE, form.get("audience"));
        assertEquals("https://www.googleapis.com/auth/cloud-platform", form.get("scope"));
        assertEquals("urn:ietf:params:oauth:token-type:access_token", form.get("requested_token_type"));
        assertEquals("urn:ietf:params:aws:token-type:aws4_request", form.get("subject_token_type"));
        Map<?, ?> subject =
                (Map<?, ?>) Json.parse(URLDecoder.decode(form.get("subject_token"), StandardCharsets.UTF_8));
        assertEquals(
                "https://sts.eu-west-1.amazonaws.com?Action=GetCallerIdentity&Version=2011-06-15", subject.get("url"));
        assertEquals("POST", subject.get("method"));
        Map<String, String> headers = new HashMap<>();
        for (Object h : (List<?>) subject.get("headers")) {
            headers.put(((String) ((Map<?, ?>) h).get("key")).toLowerCase(), (String) ((Map<?, ?>) h).get("value"));
        }
        assertEquals(
                List.of("authorization", "host", "x-amz-date", "x-amz-security-token", "x-goog-cloud-target-resource"),
                headers.keySet().stream().sorted().toList());
        assertTrue(
                headers.get("authorization").startsWith("AWS4-HMAC-SHA256 Credential=ASIACONTAINER/"),
                headers.get("authorization"));
        assertTrue(
                headers.get("authorization")
                        .contains(
                                "/eu-west-1/sts/aws4_request, SignedHeaders=host;x-amz-date;x-amz-security-token;x-goog-cloud-target-resource,"),
                headers.get("authorization"));
        assertEquals("container-session", headers.get("x-amz-security-token"));
        assertEquals(AUDIENCE, headers.get("x-goog-cloud-target-resource"));
    }

    @Test
    void tokenReusedAndFetchShared() throws Exception {
        GoogleTokens tokens = tokens(file(), Map.of());
        List<Future<String>> concurrent = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            concurrent.add(tokens.token(NEVER));
        }
        for (Future<String> f : concurrent) {
            assertEquals("google-tok", await(f));
        }
        assertEquals("google-tok", await(tokens.token(NEVER)));
        assertEquals(1, stsCalls.get(), "one exchange for the concurrent callers and the later one");
    }

    @Test
    void failuresAreNotKept() throws Exception {
        GoogleTokens tokens = tokens(file(), Map.of());
        containerStatus = 500;
        assertTrue(failure(tokens.token(NEVER)).getMessage().contains("container credentials request"));
        assertEquals(0, stsCalls.get());
        containerStatus = 200;
        stsAnswer =
                """
                        {"token_type":"Bearer","expires_in":3600,"issued_token_type":"urn:ietf:params:oauth:token-type:access_token"}""";
        assertInstanceOf(IOException.class, failure(tokens.token(NEVER)));
        stsAnswer = STS_OK;
        assertEquals("google-tok", await(tokens.token(NEVER)));
    }

    @Test
    void impersonation() throws Exception {
        Map<String, Object> file = file();
        file.put(
                "service_account_impersonation_url",
                "https://iam.test/v1/projects/-/serviceAccounts/sa@p.iam.gserviceaccount.com:generateAccessToken");
        assertEquals("sa-tok", await(tokens(file, Map.of()).token(NEVER)));
        MockUpstream.Seen impersonate = mock.seen("/v1/projects/").getFirst();
        assertEquals("Bearer google-tok", impersonate.headers().get("Authorization"));
        assertEquals(
                Json.parse(
                        """
                                {"delegates":[],"scope":["https://www.googleapis.com/auth/cloud-platform"],"lifetime":"3600s"}"""),
                Json.parse(impersonate.body()));
    }

    @Test
    void malformedFileFailsTheFetchAndIsNotKept() throws Exception {
        Path path = dir.resolve("wif.json");
        Files.writeString(path, "{not json");
        System.setProperty("aws.containerCredentialsFullUri", mock.url() + "/ecs/creds");
        GoogleTokens tokens = new GoogleTokens(
                vertx, Config.from(Map.of("GOOGLE_APPLICATION_CREDENTIALS", path.toString())), this::local);
        assertTrue(failure(tokens.token(NEVER)).getMessage().contains("no Application Default Credentials"));
        assertTrue(mock.seen().isEmpty());
        Files.writeString(path, Json.encode(file()));
        assertEquals("google-tok", await(tokens.token(NEVER)));
    }

    @Test
    void clientGoneStopsWaitingOnly() throws Exception {
        GoogleTokens tokens = tokens(file(), Map.of());
        Promise<Void> aborted = Promise.promise();
        Future<String> gone = tokens.token(aborted.future());
        Future<String> other = tokens.token(NEVER);
        aborted.complete();
        assertInstanceOf(ClientGoneException.class, failure(gone));
        assertEquals("google-tok", await(other));
    }

    @Test
    void stsRefusalFailsTheFetch() throws Exception {
        mock.on("/sts/token", r -> r.response().setStatusCode(400).end("{\"error\":\"invalid_grant\"}"));
        assertInstanceOf(IOException.class, failure(tokens(file(), Map.of()).token(NEVER)));
    }

    @Test
    void quotaProject() throws Exception {
        mock.reply(
                "/token",
                200,
                Map.of("Content-Type", "application/json"),
                """
                        {"access_token":"adc-tok","expires_in":3600,"token_type":"Bearer"}""");
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        String key = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder()
                        .encodeToString(generator.generateKeyPair().getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        // a service account: google-auth takes its token_uri, which the fake answers
        Path adc = dir.resolve("adc.json");
        Files.writeString(
                adc,
                Json.encode(Map.of(
                        "type",
                        "service_account",
                        "client_email",
                        "sa@p.iam.gserviceaccount.com",
                        "private_key",
                        key,
                        "private_key_id",
                        "k1",
                        "client_id",
                        "1",
                        "quota_project_id",
                        "q",
                        "token_uri",
                        mock.url() + "/token")));
        GoogleTokens.Credential file =
                await(new GoogleTokens(vertx, Config.from(Map.of("GOOGLE_APPLICATION_CREDENTIALS", adc.toString())))
                        .credential(NEVER));
        assertEquals("adc-tok", file.token().value());
        assertEquals("q", file.quotaProject());
        GoogleTokens.Credential setting = await(new GoogleTokens(
                        vertx,
                        Config.from(Map.of(
                                "GOOGLE_APPLICATION_CREDENTIALS", adc.toString(), "GOOGLE_CLOUD_QUOTA_PROJECT", "r")))
                .credential(NEVER));
        assertEquals("r", setting.quotaProject());
        Map<String, Object> wif = file();
        wif.put("quota_project_id", "q");
        assertEquals("q", await(tokens(wif, Map.of()).credential(NEVER)).quotaProject());
    }

    private static Map<String, String> form(String body) {
        Map<String, String> form = new HashMap<>();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            form.put(
                    URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return form;
    }

    private static Throwable failure(Future<?> future) {
        return assertThrows(ExecutionException.class, () -> await(future)).getCause();
    }
}
