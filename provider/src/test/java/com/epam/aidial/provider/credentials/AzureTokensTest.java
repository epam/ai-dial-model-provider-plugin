package com.epam.aidial.provider.credentials;

import static com.epam.aidial.provider.testing.MockUpstream.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.core.exception.ClientAuthenticationException;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * azure-ad-auth, on a credential that stands for the SDK's chain. The chain itself (the App Service managed identity
 * request) reads the process environment, so it is not covered here.
 */
class AzureTokensTest {

    private static final Future<Void> NEVER = Promise.<Void>promise().future();
    private static final String SCOPE = "https://ai.azure.com/.default";

    private Vertx vertx;
    private final AtomicInteger created = new AtomicInteger();
    private final AtomicInteger calls = new AtomicInteger();
    private final List<String> scopes = new ArrayList<>();
    private volatile boolean failing;
    private volatile boolean withoutExpiry;
    private volatile Duration lifetime = Duration.ofHours(1);

    @BeforeEach
    void start() {
        vertx = Vertx.vertx();
    }

    @AfterEach
    void stop() throws Exception {
        await(vertx.close());
    }

    private AzureTokens tokens() {
        return new AzureTokens(vertx, () -> {
            created.incrementAndGet();
            TokenCredential credential = context -> {
                int n = calls.incrementAndGet();
                scopes.addAll(context.getScopes());
                if (failing) {
                    return Mono.error(
                            new ClientAuthenticationException("ManagedIdentityCredential authentication failed", null));
                }
                OffsetDateTime expiresAt =
                        withoutExpiry ? null : OffsetDateTime.now().plus(lifetime);
                return Mono.delay(Duration.ofMillis(100)).map(x -> new AccessToken("aad-tok-" + n, expiresAt));
            };
            return credential;
        });
    }

    @Test
    void credentialLookedUpOnFirstUse() throws Exception {
        AzureTokens tokens = tokens();
        assertEquals(0, created.get());
        assertEquals("aad-tok-1", await(tokens.token(SCOPE, NEVER)));
        assertEquals(List.of(SCOPE), scopes);
        assertEquals(1, created.get());
    }

    @Test
    void tokenReusedAndFetchShared() throws Exception {
        AzureTokens tokens = tokens();
        List<Future<String>> concurrent = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            concurrent.add(tokens.token(SCOPE, NEVER));
        }
        for (Future<String> f : concurrent) {
            assertEquals("aad-tok-1", await(f));
        }
        assertEquals("aad-tok-1", await(tokens.token(SCOPE, NEVER)));
        assertEquals(1, calls.get());
    }

    @Test
    void shortLivedTokenNotReused() throws Exception {
        lifetime = Duration.ofMinutes(4);
        AzureTokens tokens = tokens();
        assertEquals("aad-tok-1", await(tokens.token(SCOPE, NEVER)));
        assertEquals("aad-tok-2", await(tokens.token(SCOPE, NEVER)));
    }

    @Test
    void failedFetchRetried() throws Exception {
        AzureTokens tokens = tokens();
        failing = true;
        Throwable failure = assertThrows(ExecutionException.class, () -> await(tokens.token(SCOPE, NEVER)))
                .getCause();
        assertInstanceOf(AzureTokens.AzureAdException.class, failure);
        failing = false;
        assertEquals("aad-tok-2", await(tokens.token(SCOPE, NEVER)));
        assertEquals(1, created.get(), "the credential is kept, a failed fetch is not");
    }

    @Test
    void tokenWithoutExpiryIsAnAuthenticationFailure() {
        AzureTokens tokens = tokens();
        withoutExpiry = true;
        Throwable failure = assertThrows(ExecutionException.class, () -> await(tokens.token(SCOPE, NEVER)))
                .getCause();
        assertInstanceOf(AzureTokens.AzureAdException.class, failure);
        assertEquals("azure AD token unavailable: the credential chain returned no usable token", failure.getMessage());
    }
}
