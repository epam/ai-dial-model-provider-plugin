package com.epam.aidial.provider.anthropic;

import static com.epam.aidial.provider.testing.RoutesFixture.collect;
import static com.epam.aidial.provider.testing.RoutesFixture.read;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.epam.aidial.provider.api.AdapterResponse;
import com.epam.aidial.provider.core.Config;
import com.epam.aidial.provider.core.DialApi;
import com.epam.aidial.provider.core.Routes;
import com.epam.aidial.provider.credentials.AwsCredentials;
import com.epam.aidial.provider.credentials.AzureAd;
import com.epam.aidial.provider.credentials.Secret;
import com.epam.aidial.provider.credentials.SessionTags;
import com.epam.aidial.provider.credentials.SigV4;
import com.epam.aidial.provider.testing.MockUpstream;
import com.epam.aidial.provider.testing.RoutesFixture;
import com.epam.aidial.provider.upstream.UpstreamClient;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

/** anthropic-upstreams (bedrock), bedrock-event-stream and aws-session-tags on the route. */
class BedrockRoutesTest {

    private static String invalid(String message) {
        return "{\"error\":{\"message\":\"" + message + "\",\"type\":\"invalid_request_error\"},\"type\":\"error\"}\n";
    }

    private static final String AWS_ROLE = apiError("Failed to assume the configured AWS role");
    private static final String AWS_CHAIN = apiError("Failed to resolve AWS credentials for Bedrock. Verify your AWS "
            + "profile, environment variables, or runtime identity configuration and try again.");

    private static String apiError(String message) {
        return "{\"error\":{\"message\":\"" + message + "\",\"type\":\"api_error\"},\"type\":\"error\"}\n";
    }

    private static final Pattern AUTH =
            Pattern.compile("AWS4-HMAC-SHA256 Credential=(\\w+)/(\\d{8})/([\\w-]+)/([\\w-]+)/aws4_request, "
                    + "SignedHeaders=([^,]+), Signature=(\\w+)");
    private static final Map<String, String> SECRETS =
            Map.of("AKID", "SECRET", "AKIDCHAIN", "chainsecret", "AKIDSTS", "stssecret");

    @RegisterExtension
    final RoutesFixture fx = new RoutesFixture();

    @BeforeEach
    void start() {
        fx.mock()
                .reply("/model/", 200, Map.of("Content-Type", "application/json"), "{\"id\":\"msg_1\"}")
                .reply("/anthropic/", 200, Map.of("Content-Type", "application/json"), "{\"id\":\"msg_2\"}")
                .reply("/v1/messages", 200, Map.of("Content-Type", "application/json"), "{\"id\":\"msg_3\"}")
                .on("/v1/user/info", r -> r.response()
                        .end("{\"roles\":[\"r1\"],\"project\":\"" + r.getHeader("Api-Key") + "\"}"))
                .on("/", r -> {
                    MockUpstream.Seen seen = fx.mock().last();
                    if (seen.body().contains("FAILROLE")) {
                        r.response()
                                .setStatusCode(403)
                                .putHeader("Content-Type", "text/xml")
                                .end(
                                        "<ErrorResponse><Error><Type>Sender</Type>"
                                                + "<Code>AccessDenied</Code><Message>not authorized</Message></Error><RequestId>r1</RequestId></ErrorResponse>");
                        return;
                    }
                    r.response()
                            .putHeader("Content-Type", "text/xml")
                            .end(
                                    """
                                    <AssumeRoleResponse xmlns="https://sts.amazonaws.com/doc/2011-06-15/"><AssumeRoleResult><Credentials><AccessKeyId>AKIDSTS</AccessKeyId><SecretAccessKey>stssecret</SecretAccessKey><SessionToken>ststoken</SessionToken><Expiration>2099-01-01T00:00:00Z</Expiration></Credentials></AssumeRoleResult></AssumeRoleResponse>""");
                });
    }

    /** Null removes a setting. */
    private Routes bedrock(String... overrides) {
        Map<String, String> s = new HashMap<>(Map.of(
                "AWS_DEFAULT_REGION",
                "us-east-1",
                "AWS_ACCESS_KEY_ID",
                "AKID",
                "AWS_SECRET_ACCESS_KEY",
                "SECRET",
                "ANTHROPIC_BEDROCK_BASE_URL",
                fx.mock().url(),
                "ANTHROPIC_BEDROCK_MANTLE_BASE_URL",
                fx.mock().url() + "/anthropic",
                "ANTHROPIC_BASE_URL",
                fx.mock().url(),
                "AWS_ENDPOINT_URL_STS",
                fx.mock().url(),
                "DIAL_URL",
                fx.mock().url()));
        for (int i = 0; i < overrides.length; i += 2) {
            if (overrides[i + 1] == null) {
                s.remove(overrides[i]);
            } else {
                s.put(overrides[i], overrides[i + 1]);
            }
        }
        Config config = Config.from(s);
        AwsCredentials aws = new AwsCredentials(
                fx.vertx(),
                config,
                () -> StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIDCHAIN", "chainsecret")));
        SessionTags tags = SessionTags.of(config, DialApi.from(fx.vertx(), config));
        return Routes.of(new AnthropicRoutes(
                "/compat/bedrock",
                AnthropicTargets.bedrock(config, aws, tags),
                new UpstreamClient(fx.vertx(), config.proxies(), config.bedrock()),
                new AzureAd((scope, aborted) -> Future.failedFuture("no Azure"), "s"),
                aborted -> Future.failedFuture("no Google"),
                RoutesFixture.CLOCK));
    }

    @Test
    void invokeStream() throws Exception {
        Buffer events = chunk("""
                {"type":"message_start","message":{"id":"m"}}""")
                .appendBuffer(frame(Map.of(":message-type", "event", ":event-type", "metrics"), "{}"))
                .appendBuffer(chunk("{\"delta\":{\"text\":\"a\\nb\"},\"type\":\"content_block_delta\"}"))
                .appendBuffer(chunk("{\"no\":\"type\"}"));
        fx.mock().on("/model/", r -> {
            // Split mid-frame: the decoder keeps what it has not read.
            r.response()
                    .setChunked(true)
                    .putHeader("Content-Type", "application/vnd.amazon.eventstream")
                    .putHeader("Content-Encoding", "x");
            r.response().write(events.getBuffer(0, 30));
            fx.vertx().setTimer(20, t -> r.response().end(events.getBuffer(30, events.length())));
        });
        AdapterResponse response = fx.post(
                bedrock(),
                "/anthropic/v1/messages",
                """
                        {"model":"anthropic.claude-x:0","stream":true,"max_tokens":5,"messages":[]}""",
                "Anthropic-Beta",
                "context-1m-2025-08-07,oauth-2025-04-20",
                "Accept-Encoding",
                "gzip",
                "Content-Type",
                "text/plain");

        assertEquals(200, response.status());
        assertEquals("text/event-stream", response.headers().get("Content-Type"));
        assertNull(response.headers().get("Content-Encoding"));
        assertEquals(
                "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"m\"}}\n\n"
                        + "event: content_block_delta\ndata: {\"delta\":{\"text\":\"a\\nb\"},\"type\":\"content_block_delta\"}\n\n"
                        + "event: completion\ndata: {\"no\":\"type\"}\n\n",
                read(response));
        MockUpstream.Seen seen = fx.mock().last();
        assertEquals("/model/anthropic.claude-x:0/invoke-with-response-stream", seen.path());
        assertEquals(
                """
                        {"max_tokens":5,"messages":[],"anthropic_version":"bedrock-2023-05-31","anthropic_beta":["context-1m-2025-08-07"]}""",
                seen.body());
        assertNull(seen.headers().get("Accept-Encoding"), "the event stream is decoded here: it comes uncompressed");
        assertNull(seen.headers().get("Anthropic-Version"), "in the body");
        assertSigned(seen, "AKID", "us-east-1", "bedrock");
    }

    @Test
    void invokeForwardsTheClientsAnthropicVersionAsReceived() throws Exception {
        fx.mock().on("/model/", r -> r.response().end("{}"));
        String body = "{\"model\":\"m\",\"messages\":[]}";

        fx.post(bedrock(), "/anthropic/v1/messages", body, "Anthropic-Version", "");
        assertEquals("", fx.mock().last().headers().get("Anthropic-Version"), "an empty value too");
        fx.post(bedrock(), "/anthropic/v1/messages", body, "Anthropic-Version", "2023-01-01");
        assertEquals("2023-01-01", fx.mock().last().headers().get("Anthropic-Version"));
    }

    @Test
    void exceptionMidStreamTruncatesAfterTheEventsBeforeIt() throws Exception {
        Buffer events = chunk("{\"type\":\"message_start\"}")
                .appendBuffer(frame(
                        Map.of(":message-type", "exception", ":exception-type", "throttlingException"),
                        "{\"message\":\"Too many tokens\"}"))
                .appendBuffer(chunk("{\"type\":\"never\"}"));
        fx.mock().on("/model/", r -> r.response()
                .putHeader("Content-Type", "application/vnd.amazon.eventstream")
                .end(events));
        AdapterResponse response =
                fx.post(bedrock(), "/anthropic/v1/messages", "{\"model\":\"m\",\"stream\":true,\"messages\":[]}");
        RoutesFixture.Collected c = collect(response);
        assertEquals(
                """
                        event: message_start\ndata: {"type":"message_start"}\n\n""",
                c.received().toString());
        assertTrue(c.failed(), "the stream is broken, not ended");
    }

    @Test
    void brokenFramesFailTheStream() throws Exception {
        Buffer good = chunk("{\"type\":\"a\"}");
        Buffer badPrelude = good.copy();
        badPrelude.setByte(8, (byte) (badPrelude.getByte(8) ^ 1));
        Buffer badMessage = good.copy();
        badMessage.setByte(good.length() - 1, (byte) (badMessage.getByte(good.length() - 1) ^ 1));
        for (Buffer body : List.of(
                badPrelude,
                badMessage,
                good.getBuffer(0, good.length() - 3),
                good.getBuffer(0, 5),
                frame(Map.of(":message-type", "event", ":event-type", "chunk"), "{\"nobytes\":1}"))) {
            fx.mock().on("/model/", r -> r.response()
                    .putHeader("Content-Type", "application/vnd.amazon.eventstream")
                    .end(body));
            RoutesFixture.Collected c = collect(
                    fx.post(bedrock(), "/anthropic/v1/messages", "{\"model\":\"m\",\"stream\":true,\"messages\":[]}"));
            assertTrue(c.failed());
            assertEquals("", c.received().toString());
        }
    }

    @Test
    void mantle() throws Exception {
        AdapterResponse response = fx.post(
                bedrock(),
                "/anthropic/v1/messages/count_tokens",
                """
                        {"model":"anthropic.claude-x","messages":[]}""",
                "X-Upstream-Extra-Data",
                """
                        {"claude_client":"mantle","region":"eu-west-1"}""",
                "Accept-Encoding",
                "gzip");
        assertEquals(200, response.status());
        assertEquals("{\"id\":\"msg_2\"}", read(response));
        MockUpstream.Seen seen = fx.mock().last();
        assertEquals("/anthropic/v1/messages/count_tokens", seen.path());
        assertEquals(
                """
                        {"model":"anthropic.claude-x","messages":[]}""",
                seen.body(),
                "Mantle takes the Messages API's body as it is");
        assertEquals("2023-06-01", seen.headers().get("Anthropic-Version"));
        assertEquals("gzip", seen.headers().get("Accept-Encoding"));
        assertSigned(seen, "AKID", "eu-west-1", "bedrock-mantle");
    }

    @Test
    void invalidUtf8Refused() throws Exception {
        byte[] notUtf8 = {'{', '"', 'm', 'o', 'd', 'e', 'l', '"', ':', '"', 'm', (byte) 0xff, '"', '}'};
        AdapterResponse signed = fx.post(
                bedrock(),
                "/anthropic/v1/messages",
                notUtf8,
                "X-Upstream-Extra-Data",
                "{\"claude_client\":\"mantle\"}");
        assertEquals(400, signed.status(), "not JSON: refused before it is signed");
        assertEquals(invalid("The request body is not valid JSON: Invalid UTF-8 start byte 0xff"), read(signed));

        AdapterResponse relayed = fx.post(bedrock(), "/anthropic/v1/messages", notUtf8, "X-Upstream-Key", "k");
        assertEquals(400, relayed.status());
        assertTrue(fx.mock().seen().isEmpty());
    }

    @Test
    void platformKey() throws Exception {
        assertEquals(
                "{\"id\":\"msg_3\"}",
                read(fx.post(bedrock(), "/anthropic/v1/messages", "{\"model\":\"m\"}", "X-Upstream-Key", "k")));
        assertEquals("k", fx.mock().last().headers().get("X-Api-Key"));
        assertNull(fx.mock().last().headers().get("Authorization"));
    }

    @Test
    void configurationsTheClientFactoryRejects() throws Exception {
        for (String extra : List.of(
                "[1]",
                "null",
                "{\"aws_access_key_id\":1}",
                "{\"region\":null}",
                "{\"claude_client\":null}",
                "{\"claude_client\":\"converse\"}",
                "{\"claude_client\":\"nope\"}",
                """
                        {"aws_assume_role_arn":"arn:aws:iam::1:role/r","aws_access_key_id":null,"region":""}""")) {
            AdapterResponse response =
                    fx.post(bedrock(), "/anthropic/v1/messages", "{\"model\":\"m\"}", "X-Upstream-Extra-Data", extra);
            assertEquals(500, response.status(), extra);
            assertEquals("Internal Server Error", read(response), extra);
        }
        AdapterResponse noRegion =
                fx.post(bedrock("AWS_DEFAULT_REGION", null), "/anthropic/v1/messages", "{\"model\":\"m\"}");
        assertEquals("Internal Server Error", read(noRegion));
        assertTrue(fx.mock().seen().isEmpty());

        // A region in the extra data serves without AWS_DEFAULT_REGION; an empty one signs us-east-1 on the
        // invoke-model API.
        assertEquals(
                200,
                fx.post(
                                bedrock("AWS_DEFAULT_REGION", null),
                                "/anthropic/v1/messages",
                                "{\"model\":\"m\"}",
                                "X-Upstream-Extra-Data",
                                "{\"region\":\"\"}")
                        .status());
        assertSigned(fx.mock().last(), "AKID", "us-east-1", "bedrock");
    }

    @Test
    void credentialsFromTheExtraData() throws Exception {
        fx.post(
                bedrock(),
                "/anthropic/v1/messages",
                "{\"model\":\"m\"}",
                "X-Upstream-Extra-Data",
                """
                        {"aws_access_key_id":"AKIDCHAIN","aws_secret_access_key":"chainsecret","aws_session_token":"tok"}""");
        assertSigned(fx.mock().last(), "AKIDCHAIN", "us-east-1", "bedrock");
        assertEquals("tok", fx.mock().last().headers().get("X-Amz-Security-Token"));
        // Without the secret, the key id is dropped: the default chain signs.
        fx.post(
                bedrock(),
                "/anthropic/v1/messages",
                "{\"model\":\"m\"}",
                "X-Upstream-Extra-Data",
                "{\"aws_secret_access_key\":null}");
        assertSigned(fx.mock().last(), "AKIDCHAIN", "us-east-1", "bedrock");
        assertNull(fx.mock().last().headers().get("X-Amz-Security-Token"));
    }

    @Test
    void missingProfileFailsBeforeAnyCall() throws Exception {
        for (Map.Entry<String, String> expected : Map.of(
                        "{\"aws_access_key_id\":null}",
                        AWS_CHAIN,
                        """
                                {"aws_access_key_id":null,"aws_assume_role_arn":"arn:aws:iam::1:role/r"}""",
                        AWS_ROLE)
                .entrySet()) {
            String extra = expected.getKey();
            AdapterResponse response = fx.post(
                    bedrock("AWS_PROFILE", "no-such-profile", "AWS_SESSION_TAGS", "{\"p\":\"UserInfo.project\"}"),
                    "/anthropic/v1/messages",
                    "{\"model\":\"m\"}",
                    "X-Upstream-Extra-Data",
                    extra,
                    "Api-Key",
                    "ka");
            assertEquals(500, response.status(), extra);
            assertEquals(expected.getValue(), read(response), extra);
        }
        assertTrue(fx.mock().seen().isEmpty());
    }

    @Test
    void unsupportedEndpointIsAnsweredWithoutAnyCall() throws Exception {
        String role = """
                {"aws_access_key_id":null,"aws_assume_role_arn":"arn:aws:iam::1:role/r"}""";
        String notFound =
                """
                        {"error":{"message":"Endpoint not supported","type":"not_found_error"},"type":"error"}\n""";
        // a role to assume, a role that cannot be assumed, keys: the same 404, no STS call, and the body is not read
        for (String extra : List.of(role, role.replace("role/r", "role/FAILROLE"), "{}")) {
            AdapterResponse response = fx.post(
                    bedrock(), "/anthropic/v1/messages/count_tokens", "not json", "X-Upstream-Extra-Data", extra);
            assertEquals(404, response.status());
            assertEquals(notFound, read(response));
        }
        assertTrue(fx.mock().seen().isEmpty(), "nothing is called for it");
    }

    @Test
    void taggedRole() throws Exception {
        Routes routes = bedrock(
                "AWS_ACCESS_KEY_ID",
                null,
                "AWS_ASSUME_ROLE_ARN",
                "arn:aws:iam::1:role/r",
                "AWS_SESSION_TAGS",
                """
                        {"application":"Bedrock.modelId","project":"UserInfo.project"}""");
        AdapterResponse response = fx.post(
                routes,
                "/anthropic/v1/messages",
                """
                        {"model":"anthropic.claude-x","messages":[]}""",
                "Api-Key",
                "alice");
        assertEquals(200, response.status());
        List<MockUpstream.Seen> seen = fx.mock().seen();
        assertEquals(
                List.of("/v1/user/info", "/", "/model/anthropic.claude-x/invoke"),
                seen.stream().map(MockUpstream.Seen::path).toList());
        Map<String, String> form = assertAssumeRole(seen.get(1), "Project_alice");
        assertEquals("application", form.get("Tags.member.1.Key"));
        assertEquals("anthropic.claude-x", form.get("Tags.member.1.Value"));
        assertEquals("project", form.get("Tags.member.2.Key"));
        assertEquals("alice", form.get("Tags.member.2.Value"));
        assertSigned(seen.get(2), "AKIDSTS", "us-east-1", "bedrock");
        assertEquals("ststoken", seen.get(2).headers().get("X-Amz-Security-Token"));

        // The same session: the role's credentials are reused; another user's: assumed again.
        fx.post(
                routes,
                "/anthropic/v1/messages",
                """
                        {"model":"anthropic.claude-x","messages":[]}""",
                "Api-Key",
                "alice");
        fx.post(
                routes,
                "/anthropic/v1/messages",
                """
                        {"model":"anthropic.claude-x","messages":[]}""",
                "Api-Key",
                "bob");
        assertEquals(
                2, fx.mock().seen().stream().filter(s -> s.path().equals("/")).count());

        response = fx.post(
                routes,
                "/anthropic/v1/messages",
                """
                        {"model":"anthropic.claude-x","messages":[]}""",
                "Api-Key",
                "");
        assertEquals("Internal Server Error", read(response));
    }

    @Test
    void bodyRefusedBeforeTheRoleIsAssumed() throws Exception {
        Routes routes = bedrock("AWS_ACCESS_KEY_ID", null, "AWS_ASSUME_ROLE_ARN", "arn:aws:iam::1:role/r");
        AdapterResponse response = fx.post(routes, "/anthropic/v1/messages", "[1]");
        assertEquals(400, response.status());
        assertEquals(
                invalid("The request body must be a JSON object, not an array"),
                read(response),
                "a body that is not an object is refused without STS");
        response = fx.post(routes, "/anthropic/v1/messages", "{\"messages\":[]}");
        assertEquals(invalid("model: Field required"), read(response), "so is a body without a model");
        assertTrue(fx.mock().seen().isEmpty());
    }

    @Test
    void eventTypes() {
        assertEquals("message_start", EventStreamToSse.eventType("{\"type\":\"message_start\"}".getBytes()));
        assertEquals("ping", EventStreamToSse.eventType("{\"a\":1,\"type\":\"ping\"}".getBytes()));
        assertEquals("a\"b", EventStreamToSse.eventType("{\"type\":\"a\\\"b\"}".getBytes()));
        for (String data : List.of("{\"type\":\"\"}", "{\"type\":1}", "[]", "nope", "{\"other\":\"x\"}")) {
            assertEquals("completion", EventStreamToSse.eventType(data.getBytes()), data);
        }
    }

    /** Checks the signature as AWS verifies it: recomputed over the headers it names. */
    private static void assertSigned(MockUpstream.Seen seen, String keyId, String region, String service) {
        String auth = seen.headers().get("Authorization");
        Matcher m = AUTH.matcher(auth == null ? "" : auth);
        assertTrue(m.matches(), auth);
        assertEquals(List.of(keyId, region, service), List.of(m.group(1), m.group(3), m.group(4)));
        Map<String, String> signed = new TreeMap<>();
        for (String name : m.group(5).split(";")) {
            if (!List.of("host", "x-amz-date", "x-amz-security-token").contains(name)) {
                signed.put(name, seen.headers().get(name));
            }
        }
        Instant at = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                .withZone(ZoneOffset.UTC)
                .parse(seen.headers().get("X-Amz-Date"), Instant::from);
        String token = seen.headers().get("X-Amz-Security-Token");
        Map<String, String> again = SigV4.sign(
                seen.method(),
                URI.create("http://" + seen.headers().get("Host") + seen.uri()),
                signed,
                seen.body().getBytes(StandardCharsets.UTF_8),
                region,
                service,
                new SigV4.Credentials(keyId, new Secret(SECRETS.get(keyId)), Secret.of(token)),
                at);
        assertEquals(again.get("Authorization"), auth);
    }

    private static Map<String, String> assertAssumeRole(MockUpstream.Seen seen, String sessionName) {
        Map<String, String> form = new LinkedHashMap<>();
        for (String pair : seen.body().split("&")) {
            int eq = pair.indexOf('=');
            form.put(
                    URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        assertEquals("AssumeRole", form.get("Action"));
        assertEquals(sessionName, form.get("RoleSessionName"));
        assertFalse(form.containsKey("DurationSeconds"), "the role's default duration, as the other adapters ask");
        assertTrue(
                seen.headers().get("Authorization").contains("Credential=AKIDCHAIN/"), "signed with the default chain");
        return form;
    }

    static Buffer chunk(String json) {
        String bytes = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(":event-type", "chunk");
        headers.put(":content-type", "application/json");
        headers.put(":message-type", "event");
        return frame(headers, "{\"bytes\":\"" + bytes + "\",\"p\":\"abcdefgh\"}");
    }

    static Buffer frame(Map<String, String> headers, String payload) {
        Buffer h = Buffer.buffer();
        headers.forEach((name, value) -> {
            byte[] v = value.getBytes(StandardCharsets.UTF_8);
            h.appendByte((byte) name.length())
                    .appendString(name)
                    .appendByte((byte) 7)
                    .appendUnsignedShort(v.length)
                    .appendBytes(v);
        });
        byte[] p = payload.getBytes(StandardCharsets.UTF_8);
        Buffer m = Buffer.buffer().appendInt(12 + h.length() + p.length + 4).appendInt(h.length());
        m.appendUnsignedInt(crc(m));
        m.appendBuffer(h).appendBytes(p);
        return m.appendUnsignedInt(crc(m));
    }

    private static long crc(Buffer b) {
        CRC32 crc = new CRC32();
        crc.update(b.getBytes());
        return crc.getValue();
    }
}
