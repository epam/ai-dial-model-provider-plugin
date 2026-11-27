package com.epam.aidial.provider.anthropic;

import com.epam.aidial.provider.api.AdapterRequest;
import com.epam.aidial.provider.api.AdapterResponse;
import com.epam.aidial.provider.api.Body;
import com.epam.aidial.provider.api.CacheBreakpoint;
import com.epam.aidial.provider.api.InvalidRequestException;
import com.epam.aidial.provider.core.Buffers;
import com.epam.aidial.provider.core.Routes;
import com.epam.aidial.provider.core.Unsent;
import com.epam.aidial.provider.credentials.Authorizer;
import com.epam.aidial.provider.credentials.AzureAd;
import com.epam.aidial.provider.credentials.GoogleTokenSource;
import com.epam.aidial.provider.json.BodyFields;
import com.epam.aidial.provider.upstream.CredentialsUnavailableException;
import com.epam.aidial.provider.upstream.Relay;
import com.epam.aidial.provider.upstream.UpstreamClient;
import com.epam.aidial.provider.upstream.UpstreamRefusedException;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpMethod;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;

public final class AnthropicRoutes implements Routes.Group {

    private static final String BASE = "/anthropic";

    public static final List<String> PATHS =
            Arrays.stream(Endpoint.values()).map(AnthropicRoutes::route).toList();

    private final String prefix;
    private final AnthropicTargets.Resolver resolver;
    private final UpstreamClient client;
    private final UpstreamAuth auth;
    private final Clock clock;
    private final AnthropicErrors.Failures failures = new AnthropicErrors.Failures();

    /**
     * @param prefix the adapter's mount point, used only in the logs
     */
    public AnthropicRoutes(
            String prefix,
            AnthropicTargets.Resolver resolver,
            UpstreamClient client,
            AzureAd azure,
            GoogleTokenSource google,
            Clock clock) {
        this.prefix = prefix;
        this.resolver = resolver;
        this.client = client;
        this.auth = new UpstreamAuth(azure, google, clock);
        this.clock = clock;
    }

    @Override
    public void register(Routes routes) {
        for (Endpoint endpoint : Endpoint.values()) {
            routes.add(HttpMethod.POST, route(endpoint), request -> handle(request, endpoint));
        }
    }

    private static String route(Endpoint endpoint) {
        return BASE + endpoint.path();
    }

    private enum Delivery {
        /** Read whole before anything is written: a stalled or cut body is an internal failure, not a cut 200. */
        WHOLE,
        STREAM,
        EVENT_STREAM;

        static Delivery of(Target target, boolean stream) {
            if (!stream) {
                return WHOLE;
            }
            return target.invokeApi() ? EVENT_STREAM : STREAM;
        }

        boolean streams() {
            return this != WHOLE;
        }
    }

    Future<AdapterResponse> handle(AdapterRequest request, Endpoint endpoint) {
        String path = prefix + request.path();
        Target target;
        try {
            target = resolver.resolve(request);
        } catch (UpstreamRefusedException | CredentialsUnavailableException e) {
            return failures.answer(e, path);
        }
        if (!target.serves(endpoint)) {
            // Before the body is read and before any credential is fetched: no call is made for this answer.
            return Future.succeededFuture(failures.endpointNotSupported());
        }
        try {
            ClientBody body = ClientBody.parse(Buffers.array(request.body()), endpoint);
            return forward(target, endpoint, request, body, path);
        } catch (InvalidRequestException e) {
            return failures.answer(e, path);
        }
    }

    /**
     * @throws InvalidRequestException before anything is sent, when the model cannot be named in the upstream's URL
     */
    private Future<AdapterResponse> forward(
            Target target, Endpoint endpoint, AdapterRequest request, ClientBody body, String path) {
        // Delivery and URL come from the body as received; the URL is also the last check of the model.
        Delivery delivery = Delivery.of(target, body.stream());
        String url = target.url(endpoint, body.model(), delivery.streams());
        // Not the request: it holds the body, which the callbacks below must not keep alive (see Unsent).
        Future<Void> aborted = request.aborted();
        Future<Authorizer> authorizer = auth.forRequest(target, request.headers(), request.scope(), body.model());

        MultiMap headers = AnthropicHeaders.toUpstream(request.headers(), target);
        BodyFields fields = body.fields();
        CacheBreakpoint breakpoint = body.breakpoint();
        boolean changed = Stripping.dropServerTools(fields, target.cloud().unsupportedTools);
        changed |= CloudBody.adapt(fields, target, endpoint, headers.get("Anthropic-Beta"));
        Unsent unsent = new Unsent(changed ? fields.encode() : body.bytes());
        return authorizer
                .compose(authorize -> authorize
                        .authorize(HttpMethod.POST, url, headers, unsent.peek())
                        .compose(v -> client.send(HttpMethod.POST, url, headers, unsent.take(), aborted)))
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
                    case WHOLE -> Relay.Success.whole(AnthropicHeaders::successToClient);
                    case STREAM ->
                        Relay.Success.streamed(
                                AnthropicHeaders::successToClient, UpstreamClient.UpstreamResponse::relay);
                    case EVENT_STREAM ->
                        Relay.Success.streamed(
                                AnthropicHeaders::eventStreamToClient,
                                upstream -> new Body.Stream(new EventStreamToSse(upstream, aborted).pipe()));
                };
        return Relay.relay(response, AnthropicHeaders::errorToClient, success, breakpoint, clock);
    }
}
