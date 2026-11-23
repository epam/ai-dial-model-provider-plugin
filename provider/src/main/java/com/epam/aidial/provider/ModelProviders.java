package com.epam.aidial.provider;

import com.epam.aidial.provider.anthropic.AnthropicRoutes;
import com.epam.aidial.provider.anthropic.Targets;
import com.epam.aidial.provider.core.CompatRouter;
import com.epam.aidial.provider.core.Config;
import com.epam.aidial.provider.core.DialApi;
import com.epam.aidial.provider.core.Routes;
import com.epam.aidial.provider.credentials.AwsCredentials;
import com.epam.aidial.provider.credentials.AzureAd;
import com.epam.aidial.provider.credentials.AzureTokens;
import com.epam.aidial.provider.credentials.GoogleTokenSource;
import com.epam.aidial.provider.credentials.GoogleTokens;
import com.epam.aidial.provider.credentials.SessionTags;
import com.epam.aidial.provider.openai.ResponsesRoutes;
import com.epam.aidial.provider.translation.ApiDispatch;
import com.epam.aidial.provider.translation.MessagesOverResponses;
import com.epam.aidial.provider.translation.TranslatorMount;
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
import org.jspecify.annotations.Nullable;

public final class ModelProviders {

    public static final Set<String> ADAPTERS = Set.of("openai", "vertexai", "bedrock");

    private ModelProviders() {}

    public static CompatRouter create(Vertx vertx, Config config) {
        AzureAd azure = new AzureAd(new AzureTokens(vertx), config.azureOpenAiScope());
        GoogleTokens google = new GoogleTokens(vertx, config);
        Clock now = Clock.systemUTC();

        DialApi dial = DialApi.from(vertx, config);
        AwsCredentials aws = new AwsCredentials(vertx, config);

        Proxies proxies = config.proxies();
        // The openai mount's Messages and Responses routes call through one client: one cap and pool between them.
        UpstreamClient openaiClient = new UpstreamClient(vertx, proxies, config.openai());
        UpstreamClient vertexaiClient = new UpstreamClient(vertx, proxies, config.vertexai());
        UpstreamClient bedrockClient = new UpstreamClient(vertx, proxies, config.bedrock());

        Routes openai = openaiMount(vertx, config, "/compat/openai", openaiClient, azure, google, aws, dial, now);
        Routes vertexai = Routes.of(
                new AnthropicRoutes("/compat/vertexai", Targets.vertexai(config), vertexaiClient, azure, google, now));
        Routes bedrock = Routes.of(new AnthropicRoutes(
                "/compat/bedrock",
                Targets.bedrock(config, aws, SessionTags.of(config, dial)),
                bedrockClient,
                azure,
                google,
                now));

        // The translator's calls back to DIAL Core: outside the mounts' caps, as DialApi's are
        UpstreamClient dialCoreClient = new UpstreamClient(vertx, proxies, Limits.uncapped(Duration.ofMinutes(10)));
        List<UpstreamClient> clients = List.of(openaiClient, vertexaiClient, bedrockClient, dialCoreClient);
        return new CompatRouter(
                Map.of(
                        "/compat/openai",
                        openai,
                        "/compat/vertexai",
                        vertexai,
                        "/compat/bedrock",
                        bedrock,
                        TRANSLATOR_PREFIX,
                        translatorMount(dialCoreClient, config)),
                () -> close(vertx, clients, dial, aws));
    }

    /** DIAL Core's translator contract: Core calls {@code <base URL>/anthropic/v1/messages}, and is called back. */
    public static final String TRANSLATOR_PREFIX = "/openaiResponses";

    /**
     * The Messages API for a model that DIAL Core serves through its Responses API, as a service of DIAL Core.
     *
     * @param dialCoreClient what calls DIAL Core back
     */
    public static TranslatorMount translatorMount(UpstreamClient dialCoreClient, Config config) {
        return new TranslatorMount(MessagesOverResponses.overDialCore(dialCoreClient, config.dialUrl()));
    }

    /**
     * The openai mount: the Messages API routes, which also serve a Responses API upstream (translating), and the
     * Responses API routes. Both call through {@code client}.
     *
     * @param dial null without DIAL_URL
     */
    public static Routes openaiMount(
            Vertx vertx,
            Config config,
            String prefix,
            UpstreamClient client,
            AzureAd azure,
            GoogleTokenSource google,
            AwsCredentials aws,
            @Nullable DialApi dial,
            Clock now) {
        ResponsesRoutes responses = new ResponsesRoutes(
                vertx,
                config,
                prefix,
                com.epam.aidial.provider.openai.Targets.openai(config),
                client,
                azure,
                aws,
                dial,
                now);
        return Routes.of(
                new ApiDispatch(
                        AnthropicRoutes.PATHS,
                        new AnthropicRoutes(prefix, Targets.openai(), client, azure, google, now),
                        new MessagesOverResponses(responses::create)),
                responses);
    }

    /** The AWS clients are closed on a worker thread, as their close blocks. */
    private static Future<Void> close(
            Vertx vertx, List<UpstreamClient> clients, @Nullable DialApi dial, AwsCredentials aws) {
        List<Future<?>> closing = new ArrayList<>();
        clients.forEach(client -> closing.add(client.close()));
        if (dial != null) {
            closing.add(dial.close());
        }
        closing.add(vertx.executeBlocking(() -> {
            aws.close();
            return null;
        }));
        return Future.join(closing).mapEmpty();
    }
}
