package com.epam.aidial.provider.credentials;

import static com.epam.aidial.provider.testing.MockUpstream.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.epam.aidial.provider.core.Config;
import com.epam.aidial.provider.core.DialApi;
import com.epam.aidial.provider.json.Json;
import com.epam.aidial.provider.testing.MockUpstream;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sts.model.Tag;

/** aws-session-tags: model and user info tags, fitted to the AWS limits. */
class SessionTagsTest {

    private static final String MODEL = "{\"m\":\"Bedrock.modelId\"}";
    private static final String BOTH = """
            {"m":"Bedrock.modelId","p":"UserInfo.project"}""";

    private Vertx vertx;
    private MockUpstream dial;

    @BeforeEach
    void start() throws Exception {
        vertx = Vertx.vertx();
        // DIAL's user info per api-key: the project is the key's last letter.
        dial = new MockUpstream(vertx).on("/v1/user/info", r -> {
            String key = r.getHeader("Api-Key");
            r.response()
                    .end("{\"roles\":[\"r1\"],\"project\":\"" + key.substring(key.length() - 1)
                            + "\",\"userClaims\":{\"email\":\"" + key + "@b.c\"}}");
        });
    }

    @AfterEach
    void stop() throws Exception {
        await(vertx.close());
    }

    @Test
    void resolvePath() {
        Map<String, Object> info = SessionTags.parseUserInfo(
                """
                        {"roles":["r1","r2"],"project":"p","userClaims":{"email":["a@b.c"]},"extra":{"a":"v"}}"""
                        .getBytes(StandardCharsets.UTF_8));
        Map.of(
                        "userClaims.email.0",
                        "a@b.c",
                        "roles.-1",
                        "r2",
                        "roles. 0",
                        "r1",
                        "roles.+1",
                        "r2",
                        "roles.-2",
                        "r1",
                        "roles",
                        "[\"r1\",\"r2\"]",
                        "userClaims",
                        "{\"email\":[\"a@b.c\"]}",
                        "project",
                        "p",
                        "extra.a",
                        "v")
                .forEach((path, want) -> assertEquals(want, SessionTags.resolvePath(info, path), path));
        for (String path : List.of(
                "roles.2",
                "roles.-3",
                "roles.",
                "roles.0_0",
                "roles.x",
                "project.x",
                "userClaims.nope",
                "nope",
                "roles.0.x",
                "")) {
            assertNull(SessionTags.resolvePath(info, path), path);
        }
        // A project or userClaims that is null, or absent: nothing below it; the project itself is null.
        for (String user :
                List.of("{\"roles\":[]}", """
                {"roles":[],"project":null,"userClaims":null}""")) {
            Map<String, Object> u = SessionTags.parseUserInfo(user.getBytes(StandardCharsets.UTF_8));
            for (String path : List.of("userClaims.x", "project.x", "project.0")) {
                assertNull(SessionTags.resolvePath(u, path), user + " " + path);
            }
            assertEquals("null", SessionTags.resolvePath(u, "project"), user);
        }
    }

    @Test
    void userInfoAsAcceptedByTheRoutes() {
        assertEquals(
                """
                        {"roles":["r1"],"project":"p","userClaims":null,"extra":1}""",
                Json.encode(
                        SessionTags.parseUserInfo("{\"extra\":1,\"roles\":[\"r1\"],\"project\":\"p\"}".getBytes())));
        for (String bad : List.of(
                "<html>",
                "[]",
                "{\"project\":\"p\"}",
                "{\"roles\":[1]}",
                "{\"roles\":[],\"project\":1}",
                "{\"roles\":[],\"userClaims\":[]}",
                "{\"roles\":[]} {}")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> SessionTags.parseUserInfo(bad.getBytes(StandardCharsets.UTF_8)),
                    bad);
        }
    }

    @Test
    void fittedToAwsLimits() {
        String model = SessionTags.MODEL_SOURCE;
        String project = SessionTags.PROJECT_SOURCE;
        String longValue = "v".repeat(300);
        String k130 = "k".repeat(130);
        String k129x = "k".repeat(129) + "x";
        assertEquals(
                "Project_proj_é_x_y: project=proj é/x_y roles=__r1__ _r2__",
                describe(fit(
                        tag("project", project, "proj é/x,y"), tag("roles", "UserInfo.roles", "[\"r1\", \"r2\"]"))));
        assertEquals(
                "BedrockAccessSession: a=" + longValue.substring(0, 256), describe(fit(tag("a", model, longValue))));
        assertEquals(
                "BedrockAccessSession: application=m team_=e team__1=e",
                describe(fit(
                        tag("application", model, "m"),
                        tag("team!", "UserInfo.e", "e"),
                        tag("team?", "UserInfo.e", "e"))));
        assertEquals("BedrockAccessSession:", describe(fit(tag("", "UserInfo.roles.0", "r1"))));
        assertEquals(
                "BedrockAccessSession: " + k130.substring(0, 128) + "=m " + k130.substring(0, 126) + "_1=m",
                describe(fit(tag(k130, model, "m"), tag(k129x, model, "m"))));
        assertEquals("Project_a_½〇: p_=a_½〇", describe(fit(tag("ṕ", project, "á½〇"))));
        assertEquals(
                "Project_" + "P".repeat(56) + ": p=" + "P".repeat(100),
                describe(fit(tag("p", project, "P".repeat(100)))));
        assertEquals("BedrockAccessSession: p=null", describe(fit(tag("p", project, "null"))));
        assertEquals("BedrockAccessSession: p= q=x", describe(fit(tag("p", project, ""), tag("q", project, "x"))));
        assertEquals("Project_𝔸b: p=𝔸b", describe(fit(tag("p", project, "𝔸b"))), "characters, not UTF-16 units");

        // At most 50, an empty key dropped not counted.
        List<SessionTags.Tagged> many = new ArrayList<>();
        for (int i = 0; i < 55; i++) {
            many.add(tag("m" + i, model, "v"));
        }
        AwsCredentials.Session s = SessionTags.fit(many);
        assertEquals(50, s.tags().size());
        assertEquals("m49", s.tags().get(49).key());
        many.addFirst(tag("", model, "v"));
        assertEquals("m49", SessionTags.fit(many).tags().get(49).key());
    }

    @Test
    void modelTagsFollowTheModel() throws Exception {
        assertEquals("Project_a: m=anthropic.claude-x p=a", resolve(BOTH, "ka", "\"anthropic.claude-x\""));
        assertEquals(
                "Project_a: m=x p=a e=ka@b.c",
                resolve(
                        """
                                {"p":"UserInfo.project","e":"UserInfo.userClaims.email","m":"Bedrock.modelId"}""",
                        "ka",
                        "\"x\""));
        assertEquals("BedrockAccessSession:", resolve(MODEL, null, "null"));
        assertEquals("BedrockAccessSession:", resolve(MODEL, null, "7"));
        assertEquals("BedrockAccessSession:", resolve(MODEL, null, null));
        assertEquals("Project_a: p=a", resolve("{\"p\":\"UserInfo.project\"}", "ka", null));
        assertEquals(
                "BedrockAccessSession: m=x", resolve("{\"bad\":\"Nope.x\",\"m\":\"Bedrock.modelId\"}", null, "\"x\""));
        assertEquals("BedrockAccessSession: m=x", resolve(BOTH, null, "\"x\""), "no api-key: no user info");
        assertEquals("BedrockAccessSession: m=x", resolve(MODEL, "", "\"x\""), "an empty key with model tags only");
        assertEquals("error", resolve(BOTH, "", "\"x\""), "an empty api-key is refused");
        assertEquals(3, dial.seen().size(), "fetched only for UserInfo tags with a key");
    }

    @Test
    void withoutDialUrl() throws Exception {
        SessionTags tags = new SessionTags(sessionTags(BOTH), null);
        assertEquals(
                "BedrockAccessSession: m=x",
                describe(await(
                        tags.resolve(headers("ka"), "x", Promise.<Void>promise().future()))));
    }

    @Test
    void modelThatIsNoStringIsNotTagged() throws Exception {
        assertEquals("Project_a: p=a", resolve(BOTH, "ka", "null"));
        assertEquals("Project_a: p=a", resolve(BOTH, "ka", null));
        assertEquals("Project_b: m=gpt-5 p=b", resolve(BOTH, "kb", "\"gpt-5\""));
        assertEquals("Project_a: p=a", resolve(BOTH, "ka", "7"), "a model that is no string is not tagged");
        assertEquals(
                "Project_a: p=a",
                resolve("{\"p\":\"UserInfo.project\"}", "ka", "7"),
                "no model entry: the model is not read");
    }

    @Test
    void userInfoFailureSkipsItsTags() throws Exception {
        dial.reply("/v1/user/info", 500, Map.of(), "{}");
        assertEquals("BedrockAccessSession: m=x", resolve(BOTH, "ka", "\"x\""));
        assertEquals(1, dial.seen().size(), "a status is not retried");
    }

    private String resolve(String tags, String apiKey, String model) throws Exception {
        DialApi api = DialApi.from(vertx, Config.from(Map.of("DIAL_URL", dial.url())));
        SessionTags st = new SessionTags(sessionTags(tags), api);
        try {
            // the model as the routes pass it on: its text when the body's model is a string, else null
            String stringModel =
                    model != null && Json.parseOrNull(model.getBytes(StandardCharsets.UTF_8)) instanceof String text
                            ? text
                            : null;
            return describe(await(st.resolve(
                    headers(apiKey), stringModel, Promise.<Void>promise().future())));
        } catch (ExecutionException e) {
            return "error";
        }
    }

    private static Map<String, String> sessionTags(String json) {
        return Config.from(Map.of("AWS_SESSION_TAGS", json)).sessionTags();
    }

    private static MultiMap headers(String apiKey) {
        MultiMap h = MultiMap.caseInsensitiveMultiMap();
        if (apiKey != null) {
            h.set("Api-Key", apiKey);
        }
        return h;
    }

    private static SessionTags.Tagged tag(String key, String source, String value) {
        return new SessionTags.Tagged(key, source, value);
    }

    private static AwsCredentials.Session fit(SessionTags.Tagged... tags) {
        return SessionTags.fit(List.of(tags));
    }

    private static String describe(AwsCredentials.Session s) {
        StringBuilder b = new StringBuilder(s.name()).append(':');
        for (Tag t : s.tags()) {
            b.append(' ').append(t.key()).append('=').append(t.value());
        }
        return b.toString();
    }
}
