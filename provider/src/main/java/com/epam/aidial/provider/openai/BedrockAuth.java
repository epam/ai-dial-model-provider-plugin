package com.epam.aidial.provider.openai;

import com.epam.aidial.provider.api.Handler;
import com.epam.aidial.provider.core.Config;
import com.epam.aidial.provider.credentials.Authorizer;
import com.epam.aidial.provider.credentials.AwsCredentialSettings;
import com.epam.aidial.provider.credentials.AwsCredentials;
import com.epam.aidial.provider.credentials.BedrockToken;
import com.epam.aidial.provider.credentials.Secret;
import com.epam.aidial.provider.credentials.SessionTags;
import com.epam.aidial.provider.credentials.SigV4;
import com.epam.aidial.provider.upstream.ClientGoneException;
import com.epam.aidial.provider.upstream.CredentialsUnavailableException;
import com.epam.aidial.provider.upstream.CredentialsUnavailableException.Source;
import com.epam.aidial.provider.upstream.UpstreamRefusedException;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import java.net.URI;
import java.time.Clock;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

final class BedrockAuth {

    private static final Pattern MANTLE_HOST = Pattern.compile("^bedrock-mantle\\.([a-z0-9-]+)\\.api\\.aws$");

    private static final AwsCredentials.CredentialSet NO_CREDENTIALS =
            new AwsCredentials.CredentialSet("", new Secret(""), new Secret(""), "");

    private final AwsCredentials aws;
    private final SessionTags sessionTags;
    private final Clock clock;
    private final String bearer;
    private final AwsCredentialSettings settings;

    BedrockAuth(Config config, AwsCredentials aws, SessionTags sessionTags, Clock clock) {
        this.aws = aws;
        this.sessionTags = sessionTags;
        this.clock = clock;
        this.bearer = config.awsBearerTokenBedrock();
        this.settings = AwsCredentialSettings.of(config);
    }

    enum Key {
        /** An empty key: the default chain, the extra data ignored. */
        EMPTY,
        /** No key: the extra data's credentials, else the default chain. */
        UNSET
    }

    Future<Authorizer> credentials(
            Target t, Key key, @Nullable String model, MultiMap clientHeaders, Handler core, Future<Void> aborted) {
        if (key == Key.EMPTY && t.mantle()) {
            return refused(aborted, "The `api_key` argument must not be empty.");
        }
        AwsCredentials.CredentialSet c = key == Key.EMPTY
                ? NO_CREDENTIALS
                : settings.overlay(t.extra().aws()).credentialSet();
        return bedrockKeys(t, c, model, clientHeaders, core, aborted);
    }

    private Future<Authorizer> bedrockKeys(
            Target t,
            AwsCredentials.CredentialSet c,
            @Nullable String model,
            MultiMap clientHeaders,
            Handler core,
            Future<Void> aborted) {
        Future<SigV4.Credentials> keys = Future.succeededFuture(
                c.keyId().isEmpty()
                        ? null
                        : new SigV4.Credentials(
                                c.keyId(), c.secret(), c.token().value().isEmpty() ? null : c.token()));
        if (!c.roleArn().isEmpty()) {
            Future<AwsCredentials.Session> session = sessionTags == null
                    ? Future.succeededFuture(AwsCredentials.Session.NONE)
                    : sessionTags.resolve(clientHeaders, core, model, aborted);
            // Passed on as keys: one STS call at most per request.
            keys = aws.taggedProvider(c, t.bedrock().region(), "", session, AwsCredentials.OPENAI_ROLE_WINDOW)
                    .compose(provider -> provider.credentials(aborted));
        }
        return keys.compose(k -> {
            AwsCredentials.Provider provider = provider(k, t.bedrock().region());
            if (t.bedrock().api() == Target.BedrockApi.RUNTIME) {
                return provider.credentials(aborted)
                        .map(creds -> UpstreamAuth.header(
                                t,
                                "Authorization",
                                "Bearer "
                                        + BedrockToken.token(creds, t.bedrock().region(), clock.instant())))
                        .recover(e -> tokenFailed(aborted, e));
            }
            // Mirrors Bedrock Mantle's provider checks on static keys.
            if (k != null
                    && (k.accessKeyId().isBlank() || k.secretAccessKey().value().isBlank())) {
                return refused(
                        aborted,
                        "Static AWS credentials require non-empty `access_key_id` and `secret_access_key` values.");
            }
            if (k != null
                    && k.sessionToken() != null
                    && k.sessionToken().value().isBlank()) {
                return refused(aborted, "A static AWS `session_token` must not be empty when provided.");
            }
            if (k == null && !bearer.isEmpty()) {
                if (bearer.isBlank()) {
                    return refused(aborted, "The Bedrock bearer credential must not be empty.");
                }
                return Future.succeededFuture(UpstreamAuth.header(t, "Authorization", "Bearer " + bearer));
            }
            return Future.succeededFuture(signature(t, provider, aborted));
        });
    }

    /** The default chain's credentials are looked up per call, not kept: a role's change with every renewal. */
    private AwsCredentials.Provider provider(SigV4.@Nullable Credentials chosen, String region) {
        if (chosen != null) {
            return aborted -> Future.succeededFuture(chosen);
        }
        return aborted -> Future.<Void>succeededFuture().compose(v -> aws.provider(
                        NO_CREDENTIALS, region, "", AwsCredentials.Session.NONE, AwsCredentials.OPENAI_ROLE_WINDOW)
                .credentials(aborted));
    }

    /** Bedrock Mantle's provider refuses a proxied Authorization header, whatever credential it has. */
    Authorizer checked(Target t, Authorizer authorizer, Future<Void> aborted) {
        if (!t.mantle() || !t.proxiedAuth()) {
            return authorizer;
        }
        return (method, url, headers, body) -> refused(
                aborted, "Bedrock provider authentication cannot be combined with a custom `Authorization` header.");
    }

    /** The SigV4 signer also adds the body hash as X-Amz-Content-Sha256. */
    private Authorizer signature(Target t, AwsCredentials.Provider provider, Future<Void> aborted) {
        String region = t.bedrock().region();
        Authorizer sigV4 = Authorizer.sigV4(provider, region, "bedrock-mantle", aborted, clock);
        return (method, url, headers, body) -> {
            String host = URI.create(url).getHost();
            Matcher m = MANTLE_HOST.matcher(host == null ? "" : host.toLowerCase(Locale.ROOT));
            if (m.matches() && !m.group(1).equals(region)) {
                return refused(
                        aborted,
                        "The Bedrock endpoint region `" + m.group(1) + "` does not match the SigV4 region `" + region
                                + "`.");
            }
            return sigV4.authorize(method, url, headers, body);
        };
    }

    /** A client that has gone gets {@link ClientGoneException}, which writes nothing, not the refusal. */
    private static <T> Future<T> refused(Future<Void> aborted, String message) {
        return Future.failedFuture(ClientGoneException.of(UpstreamRefusedException.misconfigured(message), aborted));
    }

    /** The token generator's failure has no handler of its own: it answers as a plain-text 500. */
    private static <T> Future<T> tokenFailed(Future<Void> aborted, Throwable cause) {
        if (ClientGoneException.isGone(cause, aborted)) {
            return Future.failedFuture(new ClientGoneException());
        }
        return Future.failedFuture(new CredentialsUnavailableException(Source.BEDROCK_TOKEN, cause));
    }
}
