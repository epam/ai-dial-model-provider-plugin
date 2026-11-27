package com.epam.aidial.provider;

import com.epam.aidial.provider.anthropic.AnthropicRoutes;
import com.epam.aidial.provider.anthropic.AnthropicTargets;
import com.epam.aidial.provider.api.ConfigException;
import com.epam.aidial.provider.core.CompatRouter;
import com.epam.aidial.provider.core.Config;
import com.epam.aidial.provider.core.DialApi;
import com.epam.aidial.provider.core.HttpServerShim;
import com.epam.aidial.provider.core.Routes;
import com.epam.aidial.provider.credentials.AwsCredentials;
import com.epam.aidial.provider.credentials.AzureAd;
import com.epam.aidial.provider.credentials.AzureTokens;
import com.epam.aidial.provider.credentials.GoogleTokenSource;
import com.epam.aidial.provider.credentials.GoogleTokens;
import com.epam.aidial.provider.credentials.SessionTags;
import com.epam.aidial.provider.openai.OpenAiTargets;
import com.epam.aidial.provider.openai.ResponsesRoutes;
import com.epam.aidial.provider.translation.ApiDispatch;
import com.epam.aidial.provider.translation.CoreHandler;
import com.epam.aidial.provider.translation.DialCoreHttp;
import com.epam.aidial.provider.translation.MessagesOverResponses;
import com.epam.aidial.provider.upstream.Limits;
import com.epam.aidial.provider.upstream.Proxies;
import com.epam.aidial.provider.upstream.UpstreamClient;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ModelProviders {

    public static final Set<String> ADAPTERS = Set.of("openai", "vertexai", "bedrock");

    private ModelProviders() {}

    /**
     * The user info of a session tag is fetched through the core of each request (see {@link DialApi}), so no DIAL_URL
     * is needed here: a plugin's core is its host. Without a core that reaches DIAL Core, no tag takes its value from
     * the user info.
     */
    public static CompatRouter create(Vertx vertx, Config config) {
        AzureAd azure = new AzureAd(new AzureTokens(vertx), config.azureOpenAiScope());
        GoogleTokens google = new GoogleTokens(vertx, config);
        Clock now = Clock.systemUTC();

        DialApi dial = new DialApi(vertx);
        AwsCredentials aws = new AwsCredentials(vertx, config);

        Proxies proxies = config.proxies();
        // The openai mount's Messages and Responses routes call through one client: one cap and pool between them.
        UpstreamClient openaiClient = new UpstreamClient(vertx, proxies, config.openai());
        UpstreamClient vertexaiClient = new UpstreamClient(vertx, proxies, config.vertexai());
        UpstreamClient bedrockClient = new UpstreamClient(vertx, proxies, config.bedrock());

        Routes openai = openaiMount(vertx, config, "/compat/openai", openaiClient, azure, google, aws, dial, now);
        Routes vertexai = Routes.of(new AnthropicRoutes(
                "/compat/vertexai", AnthropicTargets.vertexai(config), vertexaiClient, azure, google, now));
        Routes bedrock = Routes.of(new AnthropicRoutes(
                "/compat/bedrock",
                AnthropicTargets.bedrock(config, aws, SessionTags.of(config, dial)),
                bedrockClient,
                azure,
                google,
                now));

        List<UpstreamClient> clients = List.of(openaiClient, vertexaiClient, bedrockClient);
        return new CompatRouter(
                Map.of(
                        "/compat/openai",
                        openai,
                        "/compat/vertexai",
                        vertexai,
                        "/compat/bedrock",
                        bedrock,
                        TRANSLATOR_PREFIX,
                        MessagesOverResponses.withCoreUpstream(CoreHandler::handle)),
                () -> close(vertx, clients, aws));
    }

    public static CompatRouter createTranslator() {
        return new CompatRouter(Map.of(TRANSLATOR_PREFIX, MessagesOverResponses.withCoreUpstream(CoreHandler::handle)));
    }

    /**
     * The standalone service: the user info of a session tag comes from DIAL Core over HTTP, so DIAL_URL is required
     * then.
     *
     * @throws ConfigException when AWS_SESSION_TAGS has a UserInfo source and DIAL_URL is unset
     */
    public static CompatRouter standalone(Vertx vertx, Config config) {
        if (config.dialUrl() == null && SessionTags.usesUserInfo(config)) {
            throw new ConfigException("DIAL_URL", "DIAL_URL must be set when AWS_SESSION_TAGS has a UserInfo source");
        }
        return create(vertx, config);
    }

    /**
     * The standalone server: its requests call DIAL Core back over HTTP (the {@code core} of the request), outside
     * the mounts' caps, as {@link DialApi}'s calls are. The client of the calls closes with {@code vertx}.
     *
     * @throws ConfigException see {@link #standalone}
     */
    public static HttpServerShim server(Vertx vertx, Config config) {
        CompatRouter router = standalone(vertx, config);
        UpstreamClient dialCoreClient =
                new UpstreamClient(vertx, config.proxies(), Limits.uncapped(Duration.ofMinutes(10)));
        return new HttpServerShim(router, new DialCoreHttp(dialCoreClient, config.dialUrl()));
    }

    /** DIAL Core's translator contract: Core calls {@code <base URL>/anthropic/v1/messages}, and is called back. */
    public static final String TRANSLATOR_PREFIX = "/openaiResponses";

    public static Routes openaiMount(
            Vertx vertx,
            Config config,
            String prefix,
            UpstreamClient client,
            AzureAd azure,
            GoogleTokenSource google,
            AwsCredentials aws,
            DialApi dial,
            Clock now) {
        ResponsesRoutes responses =
                new ResponsesRoutes(vertx, config, prefix, OpenAiTargets.openai(config), client, azure, aws, dial, now);
        return Routes.of(
                new ApiDispatch(
                        AnthropicRoutes.PATHS,
                        new AnthropicRoutes(prefix, AnthropicTargets.openai(), client, azure, google, now),
                        MessagesOverResponses.withOwnUpstream(responses::create)),
                responses);
    }

    /** The AWS clients are closed on a worker thread, as their close blocks. */
    private static Future<Void> close(Vertx vertx, List<UpstreamClient> clients, AwsCredentials aws) {
        List<Future<?>> closing = new ArrayList<>();
        clients.forEach(client -> closing.add(client.close()));
        closing.add(vertx.executeBlocking(() -> {
            aws.close();
            return null;
        }));
        return Future.join(closing).mapEmpty();
    }
}
