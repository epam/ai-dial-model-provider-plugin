package com.epam.aidial.provider.anthropic;

import static com.epam.aidial.provider.testing.MockUpstream.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.epam.aidial.provider.api.AdapterResponse;
import com.epam.aidial.provider.core.Config;
import com.epam.aidial.provider.core.DialApi;
import com.epam.aidial.provider.core.Routes;
import com.epam.aidial.provider.credentials.AwsCredentials;
import com.epam.aidial.provider.credentials.AzureAd;
import com.epam.aidial.provider.credentials.SessionTags;
import com.epam.aidial.provider.testing.MockUpstream;
import com.epam.aidial.provider.testing.Requests;
import com.epam.aidial.provider.testing.RoutesFixture;
import com.epam.aidial.provider.upstream.UpstreamClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * anthropic-messages "Beta flags stripped on ..." and "Server tools stripped per cloud". The sets are written out
 * here, not read from {@code Cloud}, so that a change to the feature matrix fails this test until the spec and the
 * test are changed with it.
 */
class StrippingRoutesTest {

    private static final String VERTEX_HOST = """
            {"region": "us-east5", "project": "p"}""";
    private static final String MANTLE_CLIENT = """
            {"claude_client": "mantle"}""";
    private static final String INVOKE_CLIENT = """
            {"claude_client": "legacy"}""";
    private static final String MESSAGES = "/anthropic/v1/messages";
    private static final String COUNT_TOKENS = MESSAGES + "/count_tokens";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Set<String> VERTEX_BETAS =
            Set.of("thinking-token-count-2026-05-13", "prompt-caching-scope-2026-01-05", "advisor-tool-2026-03-01");
    private static final Set<String> FOUNDRY_BETAS = Set.of("advisor-tool-2026-03-01");
    private static final Set<String> BEDROCK_BETAS = Set.of(
            "oauth-2025-04-20",
            "redact-thinking-2026-02-12",
            "thinking-token-count-2026-05-13",
            "prompt-caching-scope-2026-01-05",
            "claude-code-20250219",
            "advanced-tool-use-2025-11-20",
            "advisor-tool-2026-03-01");

    private static final String ADVISOR = """
            {"type": "advisor_20260301", "name": "advisor"}""";
    private static final String WEB_SEARCH =
            """
            {"type": "web_search_20250305", "name": "web_search", "max_uses": 2}""";
    private static final String BASH = """
            {"type": "bash_20250124", "name": "bash"}""";
    private static final String GET_WEATHER =
            """
            {"name": "get_weather", "description": "d", "input_schema": {"type": "object"}}""";
    // A client's own tool that shares a server tool's name is not a server tool.
    private static final String CUSTOM_WEB_SEARCH =
            """
            {"type": "custom", "name": "web_search", "input_schema": {"type": "object"}}""";
    private static final String UNTYPED_ADVISOR =
            """
            {"name": "advisor", "input_schema": {"type": "object"}}""";

    private static final String MESSAGE_BODY = """
            {"model": "m", "messages": []}""";
    private static final String MESSAGE_WITH_TOOLS = """
            {"model": "m", "messages": [], "tools": %s}""";
    private static final List<String> KEPT_BETAS = List.of("context-1m-2025-08-07", "interleaved-thinking-2025-05-14");

    enum Upstream {
        PLATFORM(Set.of(), Set.of()),
        FOUNDRY(FOUNDRY_BETAS, Set.of("advisor")),
        VERTEX(VERTEX_BETAS, Set.of("advisor")),
        BEDROCK_MANTLE(BEDROCK_BETAS, Set.of("advisor", "web_search")),
        BEDROCK_INVOKE(BEDROCK_BETAS, Set.of("advisor", "web_search"));

        final Set<String> betas;
        final Set<String> tools;

        Upstream(Set<String> betas, Set<String> tools) {
            this.betas = betas;
            this.tools = tools;
        }

        /** invoke-model has no count_tokens. */
        boolean serves(String endpoint) {
            return this != BEDROCK_INVOKE || endpoint.equals(MESSAGES);
        }

        /** The headers that make the route choose this upstream; the mock stands for its host. */
        Map<String, String> headers(String mock) {
            return switch (this) {
                case PLATFORM -> Map.of("X-Upstream-Key", "k");
                case FOUNDRY -> Map.of("X-Upstream-Endpoint", mock + "/anthropic/v1/messages", "X-Upstream-Key", "k");
                case VERTEX -> Map.of("X-Upstream-Extra-Data", VERTEX_HOST);
                case BEDROCK_MANTLE -> Map.of("X-Upstream-Extra-Data", MANTLE_CLIENT);
                case BEDROCK_INVOKE -> Map.of("X-Upstream-Extra-Data", INVOKE_CLIENT);
            };
        }

        /** The flags the upstream received: invoke-model takes them in the body, the others in the header. */
        List<String> betasSeen(MockUpstream.Seen seen) throws Exception {
            if (this == BEDROCK_INVOKE) {
                JsonNode flags = JSON.readTree(seen.body()).path("anthropic_beta");
                List<String> out = new ArrayList<>();
                flags.forEach(f -> out.add(f.asText()));
                return out;
            }
            List<String> out = new ArrayList<>();
            for (String line : seen.headers().getAll("Anthropic-Beta")) {
                Arrays.stream(line.split(",")).map(String::strip).forEach(out::add);
            }
            return out;
        }
    }

    @RegisterExtension
    final RoutesFixture fx = new RoutesFixture();

    private Routes vertexMount;
    private Routes bedrockMount;

    @BeforeEach
    void start() {
        fx.mock().reply("/", 200, Map.of("Content-Type", "application/json"), "{}");
        Config vertexConfig = Config.from(Map.of(
                "ANTHROPIC_VERTEX_BASE_URL",
                fx.mock().url() + "/",
                "ANTHROPIC_BASE_URL",
                fx.mock().url()));
        vertexMount = mount("/compat/vertexai", AnthropicTargets.vertexai(vertexConfig), vertexConfig);
        Config bedrockConfig = Config.from(Map.of(
                "AWS_DEFAULT_REGION", "us-east-1",
                "AWS_ACCESS_KEY_ID", "AKID",
                "AWS_SECRET_ACCESS_KEY", "SECRET",
                "ANTHROPIC_BEDROCK_BASE_URL", fx.mock().url(),
                "ANTHROPIC_BEDROCK_MANTLE_BASE_URL", fx.mock().url() + "/anthropic"));
        AwsCredentials aws = new AwsCredentials(fx.vertx(), bedrockConfig);
        bedrockMount = mount(
                "/compat/bedrock",
                AnthropicTargets.bedrock(
                        bedrockConfig, aws, SessionTags.of(bedrockConfig, DialApi.from(fx.vertx(), bedrockConfig))),
                bedrockConfig);
    }

    private Routes mount(String prefix, AnthropicTargets.Resolver resolver, Config config) {
        return Routes.of(new AnthropicRoutes(
                prefix,
                resolver,
                new UpstreamClient(fx.vertx(), config.proxies(), config.openai()),
                new AzureAd((scope, aborted) -> Future.failedFuture("no Azure"), "s"),
                aborted -> Future.succeededFuture("google-token"),
                RoutesFixture.CLOCK));
    }

    @ParameterizedTest
    @EnumSource(Upstream.class)
    void betaFlagsTheCloudLacksAreDropped(Upstream upstream) throws Exception {
        List<String> sent = new ArrayList<>(allUnsupportedBetas());
        sent.addAll(KEPT_BETAS);
        for (String endpoint : List.of(MESSAGES, COUNT_TOKENS)) {
            if (!upstream.serves(endpoint)) {
                continue;
            }
            post(upstream, endpoint, MESSAGE_BODY, String.join(", ", sent));

            List<String> expected =
                    sent.stream().filter(f -> !upstream.betas.contains(f)).toList();
            assertEquals(expected, upstream.betasSeen(fx.mock().last()), upstream + " " + endpoint);
        }
    }

    @ParameterizedTest
    @EnumSource(Upstream.class)
    void serverToolsTheCloudLacksAreDropped(Upstream upstream) throws Exception {
        for (String endpoint : List.of(MESSAGES, COUNT_TOKENS)) {
            if (!upstream.serves(endpoint)) {
                continue;
            }
            post(upstream, endpoint, MESSAGE_WITH_TOOLS.formatted(allTools()), null);

            JsonNode sent = JSON.readTree(fx.mock().last().body()).path("tools");
            assertEquals(JSON.readTree(keptTools(upstream)), sent, upstream + " " + endpoint);
        }
    }

    @ParameterizedTest
    @EnumSource(Upstream.class)
    void clientsOwnToolsAndFlagsAreKept(Upstream upstream) throws Exception {
        post(upstream, MESSAGES, MESSAGE_WITH_TOOLS.formatted("[" + GET_WEATHER + "]"), String.join(",", KEPT_BETAS));

        assertEquals(KEPT_BETAS, upstream.betasSeen(fx.mock().last()));
        assertEquals(
                JSON.readTree("[" + GET_WEATHER + "]"),
                JSON.readTree(fx.mock().last().body()).path("tools"));
    }

    private static Set<String> allUnsupportedBetas() {
        Set<String> all = new LinkedHashSet<>(BEDROCK_BETAS);
        all.addAll(VERTEX_BETAS);
        all.addAll(FOUNDRY_BETAS);
        return all;
    }

    private static String allTools() {
        return "[" + String.join(",", ADVISOR, WEB_SEARCH, BASH, GET_WEATHER, CUSTOM_WEB_SEARCH, UNTYPED_ADVISOR) + "]";
    }

    private static String keptTools(Upstream upstream) {
        List<String> kept = new ArrayList<>();
        if (!upstream.tools.contains("advisor")) {
            kept.add(ADVISOR);
        }
        if (!upstream.tools.contains("web_search")) {
            kept.add(WEB_SEARCH);
        }
        kept.addAll(List.of(BASH, GET_WEATHER, CUSTOM_WEB_SEARCH, UNTYPED_ADVISOR));
        return "[" + String.join(",", kept) + "]";
    }

    private AdapterResponse post(Upstream upstream, String endpoint, String body, String betas) throws Exception {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        upstream.headers(fx.mock().url()).forEach(headers::add);
        if (betas != null) {
            headers.add("Anthropic-Beta", betas);
        }
        Routes routes = upstream.name().startsWith("BEDROCK") ? bedrockMount : vertexMount;
        AdapterResponse response = await(routes.handle(Requests.of(
                HttpMethod.POST,
                endpoint,
                null,
                headers,
                Buffer.buffer(body.getBytes(StandardCharsets.UTF_8)),
                Promise.<Void>promise().future())));
        assertEquals(200, response.status(), upstream + " " + endpoint);
        return response;
    }
}
