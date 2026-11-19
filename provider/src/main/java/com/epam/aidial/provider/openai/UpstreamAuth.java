package com.epam.aidial.provider.openai;

import com.epam.aidial.provider.core.Config;
import com.epam.aidial.provider.credentials.Authorizer;
import com.epam.aidial.provider.credentials.AwsCredentials;
import com.epam.aidial.provider.credentials.AzureAd;
import com.epam.aidial.provider.credentials.Secret;
import com.epam.aidial.provider.credentials.SessionTags;
import com.epam.aidial.provider.upstream.UpstreamRefusedException;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import java.time.Clock;
import java.util.Set;
import org.jspecify.annotations.Nullable;

final class UpstreamAuth {

    private static final String MISSING_CREDENTIALS = "Missing credentials. Please pass an `api_key`, "
            + "`workload_identity`, `admin_api_key`, or set the `OPENAI_API_KEY` or `OPENAI_ADMIN_KEY` environment "
            + "variable.";

    private final AzureAd azure;
    private final BedrockAuth bedrockAuth;
    private final Set<String> vllmDeployments;

    UpstreamAuth(Config config, AzureAd azure, AwsCredentials aws, SessionTags sessionTags, Clock clock) {
        this.azure = azure;
        this.bedrockAuth = new BedrockAuth(config, aws, sessionTags, clock);
        this.vllmDeployments = config.vllmDeployments();
    }

    /**
     * An empty key is a key, except on Bedrock, where it takes the default chain: the SDK clients take an empty key
     * for none and fail to be made with it (a 500).
     *
     * @param model null when absent or on an endpoint without a body
     */
    Future<Authorizer> forRequest(Target target, MultiMap clientHeaders, @Nullable String model, Future<Void> aborted) {
        return credentials(target, clientHeaders, model, aborted)
                .map(authorizer -> bedrockAuth.checked(target, authorizer, aborted));
    }

    private Future<Authorizer> credentials(
            Target t, MultiMap clientHeaders, @Nullable String model, Future<Void> aborted) {
        Secret key = t.key();
        if (key != null && key.value().isEmpty()) {
            return t.bedrock() != null
                    ? bedrockAuth.credentials(t, BedrockAuth.Key.EMPTY, null, clientHeaders, aborted)
                    : Future.failedFuture(UpstreamRefusedException.misconfigured(MISSING_CREDENTIALS));
        }
        if (key != null) {
            return Future.succeededFuture(
                    t.versioned()
                            ? header(t, "Api-Key", key.value())
                            : header(t, "Authorization", "Bearer " + key.value()));
        }
        if (t.extra().vendor() == UpstreamExtraData.Vendor.ALIBABA_CLOUD
                || t.bedrock() == null
                        && (vllmModel(model) || !t.versioned() && t.baseUrl().contains("api.openai.com"))) {
            return Future.failedFuture(UpstreamRefusedException.missingKey());
        }
        if (t.bedrock() != null) {
            return bedrockAuth.credentials(t, BedrockAuth.Key.UNSET, model, clientHeaders, aborted);
        }
        return azure.token(aborted).map(token -> header(t, "Authorization", "Bearer " + token));
    }

    /**
     * The credential in a header. The v1 client's default headers, which carry the proxied ones, win over its api_key;
     * the versioned client's key or token wins.
     */
    static Authorizer header(Target t, String name, String value) {
        return t.versioned() || !t.proxiedAuth() ? Authorizer.header(name, new Secret(value)) : Authorizer.NONE;
    }

    private boolean vllmModel(@Nullable String model) {
        return model != null && vllmDeployments.contains(model);
    }
}
