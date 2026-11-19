package com.epam.aidial.provider.credentials;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.epam.aidial.provider.upstream.TokenUnavailableException;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * The Azure SDK's default credential chain, created on first use. The SDK reads its variables from the process
 * environment itself, not from Config. A failure is not kept.
 */
public final class AzureTokens implements AzureTokenSource {

    /** The Azure SDK's own margin. */
    private static final Duration MARGIN = Duration.ofMinutes(5);
    /** Above managed identity's retries of a throttled endpoint (6 retries, up to about 150 s). */
    private static final Duration FETCH_TIMEOUT = Duration.ofMinutes(3);

    private final Vertx vertx;
    private final Lazy<TokenCredential> credential;
    private final Map<String, Refreshing<String>> tokens = new ConcurrentHashMap<>();

    public AzureTokens(Vertx vertx) {
        this(vertx, () -> new DefaultAzureCredentialBuilder().build());
    }

    AzureTokens(Vertx vertx, Supplier<TokenCredential> credentials) {
        this.vertx = vertx;
        this.credential = new Lazy<>(credentials);
    }

    /** Fails with {@link AzureAdException}, or ClientGoneException when the client goes. */
    @Override
    public Future<String> token(String scope, Future<Void> aborted) {
        Refreshing<String> token = tokens.get(scope);
        if (token == null) {
            token = tokens.computeIfAbsent(scope, s -> new Refreshing<>(vertx, MARGIN, () -> fetch(s)));
        }
        return token.get(aborted);
    }

    private Refreshing.Expiring<String> fetch(String scope) {
        AccessToken token;
        try {
            token = credential
                    .get()
                    .getToken(new TokenRequestContext().addScopes(scope))
                    .block(FETCH_TIMEOUT);
        } catch (RuntimeException e) {
            throw new AzureAdException(e);
        }
        if (!isUsable(token)) {
            throw new AzureAdException(new IllegalStateException("the credential chain returned no usable token"));
        }
        return Refreshing.Expiring.at(token.getToken(), token.getExpiresAt().toInstant());
    }

    /** Without the expiry a token cannot be kept until its margin. */
    private static boolean isUsable(@Nullable AccessToken token) {
        return token != null && token.getToken() != null && !token.getToken().isEmpty() && token.getExpiresAt() != null;
    }

    public static final class AzureAdException extends TokenUnavailableException {

        public AzureAdException(Throwable cause) {
            super("azure AD token unavailable: " + cause.getMessage(), cause);
        }
    }
}
