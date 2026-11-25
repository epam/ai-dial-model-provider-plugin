package com.epam.aidial.provider.openai;

import com.epam.aidial.provider.api.AdapterRequest;
import com.epam.aidial.provider.api.AdapterResponse;
import com.epam.aidial.provider.api.Body;
import com.epam.aidial.provider.api.CacheBreakpoint;
import com.epam.aidial.provider.api.InvalidRequestException;
import com.epam.aidial.provider.core.Buffers;
import com.epam.aidial.provider.core.Config;
import com.epam.aidial.provider.core.DialApi;
import com.epam.aidial.provider.core.Routes;
import com.epam.aidial.provider.core.Unsent;
import com.epam.aidial.provider.credentials.Authorizer;
import com.epam.aidial.provider.credentials.AwsCredentials;
import com.epam.aidial.provider.credentials.AzureAd;
import com.epam.aidial.provider.credentials.SessionTags;
import com.epam.aidial.provider.upstream.Relay;
import com.epam.aidial.provider.upstream.UpstreamClient;
import com.epam.aidial.provider.upstream.UpstreamRefusedException;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public final class ResponsesRoutes implements Routes.Group {

    public static final List<String> BASES = List.of("/openai/v1/responses", "/openai/responses");

    private final Vertx vertx;
    private final String prefix;
    private final OpenAiTargets.Resolver resolver;
    private final UpstreamClient client;
    private final UpstreamAuth auth;
    private final OpenAiErrors.Failures failures;
    private final long heartbeatMillis;
    private final Clock clock;

    /**
     * @param dial null without DIAL_URL: no role is then tagged by user (the only use of DIAL Core)
     */
    public ResponsesRoutes(
            Vertx vertx,
            Config config,
            String prefix,
            OpenAiTargets.Resolver resolver,
            UpstreamClient client,
            AzureAd azure,
            AwsCredentials aws,
            DialApi dial,
            Clock clock) {
        this.vertx = vertx;
        this.prefix = prefix;
        this.resolver = resolver;
        this.client = client;
        this.auth = new UpstreamAuth(config, azure, aws, SessionTags.of(config, dial), clock);
        this.failures =
                new OpenAiErrors.Failures(config.openai().maxConnections().orElse(0));
        this.heartbeatMillis = config.heartbeatSeconds() * 1000L;
        this.clock = clock;
    }

    @Override
    public void register(Routes routes) {
        for (String base : BASES) {
            for (Endpoint endpoint : Endpoint.values()) {
                if (endpoint.byId()) {
                    routes.addById(
                            endpoint.method(), base, endpoint.suffix(), (request, id) -> handle(request, endpoint, id));
                } else {
                    routes.add(endpoint.method(), base + endpoint.suffix(), request -> handle(request, endpoint, ""));
                }
            }
        }
    }

    /**
     * Creates a response as {@code POST /openai/v1/responses} does, from a request that came by another path: the
     * Responses call of a translator whose upstream is the one this mount's request names. The body is a Responses
     * API's.
     */
    public Future<AdapterResponse> create(AdapterRequest request) {
        return handle(request, Endpoint.CREATE, "");
    }

    private enum Delivery {
        WHOLE,
        STREAM;

        static Delivery of(boolean stream) {
            return stream ? STREAM : WHOLE;
        }
    }

    /**
     * @param id empty for an endpoint without one
     */
    Future<AdapterResponse> handle(AdapterRequest request, Endpoint endpoint, String id) {
        String path = prefix + request.path();
        Target target;
        try {
            target = resolver.resolve(request);
        } catch (UpstreamRefusedException | InvalidRequestException e) {
            return failures.answer(e, path);
        }
        // Every upstream has every endpoint: no answer of "Endpoint not supported" here, as the Anthropic routes have.
        ClientBody body;
        try {
            body = ClientBody.parse(Buffers.array(request.body()), endpoint);
        } catch (InvalidRequestException e) {
            return failures.answer(e, path);
        }
        return forward(target, endpoint, id, request, body, path);
    }

    private Future<AdapterResponse> forward(
            Target target, Endpoint endpoint, String id, AdapterRequest request, ClientBody body, String path) {
        Map<String, String> clientQuery = request.queryParams();
        boolean stream = endpoint == Endpoint.RETRIEVE ? "true".equals(clientQuery.get("stream")) : body.stream();
        Delivery delivery = Delivery.of(stream);
        CacheBreakpoint breakpoint =
                endpoint == Endpoint.CREATE && request.asksForCacheBreakpoint() ? body.breakpoint() : null;
        // Not the request: it holds the body, which the callbacks below must not keep alive (see Unsent).
        Future<Void> aborted = request.aborted();
        Future<Authorizer> authorizer = auth.forRequest(target, request.headers(), body.model(), aborted);

        MultiMap headers = OpenAiHeaders.toUpstream(target, endpoint);
        Unsent unsent = new Unsent(body.bytes());
        return authorizer
                .compose(authorize -> {
                    // After the credentials: an endpoint that forms no URL is answered as any other failure to send.
                    String url = target.url(endpoint, id, clientQuery);
                    return authorize
                            .authorize(endpoint.method(), url, headers, unsent.peek())
                            .compose(v -> client.send(endpoint.method(), url, headers, unsent.take(), aborted));
                })
                .compose(response -> relay(response, delivery, breakpoint, aborted))
                .recover(e -> failures.answer(e, path));
    }

    private Future<AdapterResponse> relay(
            UpstreamClient.UpstreamResponse response,
            Delivery delivery,
            @Nullable CacheBreakpoint breakpoint,
            Future<Void> aborted) {
        Relay.Success success =
                switch (delivery) {
                    case WHOLE -> Relay.Success.whole(OpenAiHeaders::successToClient);
                    case STREAM ->
                        Relay.Success.streamed(
                                OpenAiHeaders::successToClient,
                                upstream -> new Body.Stream(
                                        new SseEvents(vertx, upstream.stream(), heartbeatMillis, aborted).pipe()));
                };
        return Relay.relay(response, OpenAiHeaders::errorToClient, success, breakpoint, clock);
    }
}
