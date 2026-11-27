package com.epam.aidial.provider.anthropic;

import com.epam.aidial.provider.api.Handler;
import com.epam.aidial.provider.credentials.Authorizer;
import com.epam.aidial.provider.credentials.AwsCredentials;
import com.epam.aidial.provider.credentials.AzureAd;
import com.epam.aidial.provider.credentials.GoogleTokenSource;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import java.time.Clock;

final class UpstreamAuth {

    private final AzureAd azure;
    private final GoogleTokenSource google;
    private final Clock clock;

    UpstreamAuth(AzureAd azure, GoogleTokenSource google, Clock clock) {
        this.azure = azure;
        this.google = google;
        this.clock = clock;
    }

    Future<Authorizer> forRequest(
            Target target, MultiMap clientHeaders, Handler core, String model, Future<Void> aborted) {
        return switch (target) {
            case Target.Bedrock aws -> signature(aws, clientHeaders, core, model, aborted);
            // Fetched now, not at authorize time: a failure answers the request.
            case Target.Foundry foundry ->
                foundry.key() == null
                        ? azure.token(aborted).map(token -> Authorizer.bearer(token))
                        : ready(Authorizer.headers(Target.FOUNDRY_KEY_HEADERS, foundry.key()));
            case Target.Platform platform -> ready(Authorizer.headers(Target.PLATFORM_KEY_HEADERS, platform.key()));
            case Target.Vertex ignored -> ready(Authorizer.bearer(() -> google.token(aborted)));
        };
    }

    private static Future<Authorizer> ready(Authorizer authorizer) {
        return Future.succeededFuture(authorizer);
    }

    private Future<Authorizer> signature(
            Target.Bedrock aws, MultiMap clientHeaders, Handler core, String model, Future<Void> aborted) {
        Future<AwsCredentials.Provider> credentials =
                switch (aws.credentials()) {
                    case BedrockCredentials.Fixed fixed -> Future.succeededFuture(fixed.provider());
                    case BedrockCredentials.TaggedPerRequest tagged ->
                        tagged.role().provider(clientHeaders, core, model, aborted);
                };
        return credentials.map(
                provider -> Authorizer.sigV4(provider, aws.signingRegion(), aws.service(), aborted, clock));
    }
}
