package com.epam.aidial.provider.credentials;

import com.epam.aidial.provider.core.Config;
import com.epam.aidial.provider.upstream.CredentialsUnavailableException;
import com.epam.aidial.provider.upstream.CredentialsUnavailableException.Source;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.profiles.ProfileFile;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.StsClientBuilder;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse;
import software.amazon.awssdk.services.sts.model.Tag;
import software.amazon.awssdk.utils.SdkAutoCloseable;

public final class AwsCredentials {

    public static final Duration BEDROCK_ROLE_WINDOW = Duration.ofMinutes(1);

    public static final Duration OPENAI_ROLE_WINDOW = Duration.ofSeconds(10);

    static final String DEFAULT_ROLE_SESSION_NAME = "BedrockAccessSession";
    private static final int MAX_SETS = 512;

    /** An empty keyId means no keys and an empty roleArn no role to assume: the default chain then. */
    public record CredentialSet(String keyId, Secret secret, Secret token, String roleArn) {}

    public record Session(String name, List<Tag> tags) {

        public static final Session NONE = new Session(DEFAULT_ROLE_SESSION_NAME, List.of());

        public Session {
            if (name.isEmpty()) {
                name = DEFAULT_ROLE_SESSION_NAME;
            }
        }
    }

    private record SetKey(String region, String client, CredentialSet set, Session session, Duration window) {}

    public interface Provider {
        Future<SigV4.Credentials> credentials(Future<Void> aborted);
    }

    private final Vertx vertx;
    private final Config config;
    // One segment: the least recently used set is the one dropped, exactly. A hit takes no lock.
    private final Cache<SetKey, Provider> sets =
            CacheBuilder.newBuilder().concurrencyLevel(1).maximumSize(MAX_SETS).build();
    private final Map<String, StsClient> stsClients = new ConcurrentHashMap<>();
    private final Set<String> profiles = ConcurrentHashMap.newKeySet(); // found; a missing one is looked up again
    private final Map<String, Long> missingUntil = new ConcurrentHashMap<>();
    private final SharedFetch<Void> profileChecks;
    private final Supplier<ProfileFile> profileFiles;
    private final Provider defaultChain;
    private final Lazy<AwsCredentialsProvider> chain;

    public AwsCredentials(Vertx vertx, Config config) {
        // the profile checkProfile checks: an embedding process's settings may name another than its environment's
        this(vertx, config, () -> DefaultCredentialsProvider.builder()
                .profileName(config.awsProfile())
                .build());
    }

    /** Tests pass fixed keys: the SDK's chain would find the developer's own credentials. */
    public AwsCredentials(Vertx vertx, Config config, Supplier<AwsCredentialsProvider> newChain) {
        this(vertx, config, newChain, ProfileFile::defaultProfileFile);
    }

    /** profileFiles is blocking: it reads the shared config and credentials files. */
    AwsCredentials(
            Vertx vertx, Config config, Supplier<AwsCredentialsProvider> newChain, Supplier<ProfileFile> profileFiles) {
        this.vertx = vertx;
        this.config = config;
        this.profileFiles = profileFiles;
        this.profileChecks = new SharedFetch<>(vertx);
        this.chain = new Lazy<>(newChain);
        this.defaultChain = refreshing(
                Source.AWS_CHAIN,
                () -> {
                    var c = chain.get().resolveCredentials();
                    String token = c instanceof AwsSessionCredentials s ? s.sessionToken() : null;
                    var keys =
                            new SigV4.Credentials(c.accessKeyId(), new Secret(c.secretAccessKey()), Secret.of(token));
                    return c.expirationTime()
                            .map(expires -> Refreshing.Expiring.at(keys, expires))
                            .orElseGet(() -> Refreshing.Expiring.never(keys));
                },
                BEDROCK_ROLE_WINDOW);
    }

    /**
     * client keeps the Claude clients ("legacy", "mantle") apart; "" lets the OpenAI Responses routes' Mantle and
     * Runtime share a set.
     */
    public Provider provider(CredentialSet c, String region, String client, Session session, Duration window) {
        try {
            return sets.get(
                    new SetKey(region, client, c, session, window), () -> newProvider(c, region, session, window));
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause()); // the loader throws nothing checked
        }
    }

    public Future<Provider> taggedProvider(
            CredentialSet c, String region, String client, Future<Session> session, Duration window) {
        return session.map(s -> provider(c, region, client, s, window));
    }

    private Provider newProvider(CredentialSet c, String region, Session session, Duration window) {
        if (!c.keyId().isEmpty()) {
            SigV4.Credentials keys = new SigV4.Credentials(
                    c.keyId(), c.secret(), c.token().value().isEmpty() ? null : c.token());
            return aborted -> Future.succeededFuture(keys);
        }
        if (!c.roleArn().isEmpty()) {
            return refreshing(Source.AWS_ROLE, () -> assumeRole(region, c.roleArn(), session), window);
        }
        return defaultChain;
    }

    /** Fails before any DIAL or STS call when AWS_PROFILE names a profile the shared files lack. */
    public Future<Void> checkProfile(Source source, Future<Void> aborted) {
        String profile = config.awsProfile();
        if (profile == null || profiles.contains(profile)) {
            return Future.succeededFuture();
        }
        return profileChecks.fetch(
                source.name(),
                () -> {
                    verifyProfile(source);
                    return null;
                },
                aborted);
    }

    /** Blocking. A missing profile is looked up again after 5 s: a fixed file serves the requests after that. */
    private void verifyProfile(Source source) {
        String profile = config.awsProfile();
        if (profile == null || profiles.contains(profile)) {
            return;
        }
        Long until = missingUntil.get(profile);
        if (until == null || System.nanoTime() - until > 0) {
            if (profileFiles.get().profile(profile).isPresent()) {
                profiles.add(profile);
                missingUntil.remove(profile);
                return;
            }
            missingUntil.put(profile, System.nanoTime() + 5_000_000_000L);
        }
        throw new CredentialsUnavailableException(source, "failed to get shared config profile, " + profile);
    }

    /** Only RoleArn, RoleSessionName and the tags are sent, so STS gives the role's default duration. */
    private Refreshing.Expiring<SigV4.Credentials> assumeRole(String region, String role, Session session) {
        AssumeRoleRequest.Builder request =
                AssumeRoleRequest.builder().roleArn(role).roleSessionName(session.name());
        if (!session.tags().isEmpty()) {
            request.tags(session.tags());
        }
        AssumeRoleResponse response = sts(region).assumeRole(request.build());
        if (response.credentials() == null) {
            throw new IllegalStateException("AssumeRole returned no credentials");
        }
        var k = response.credentials();
        return Refreshing.Expiring.at(
                new SigV4.Credentials(k.accessKeyId(), new Secret(k.secretAccessKey()), Secret.of(k.sessionToken())),
                k.expiration());
    }

    public void close() {
        stsClients.values().forEach(StsClient::close);
        if (chain.madeOrNull() instanceof SdkAutoCloseable closeable) {
            closeable.close();
        }
    }

    /** Signed with the default chain's credentials, whatever the role being assumed. */
    private StsClient sts(String region) {
        return stsClients.computeIfAbsent(region, r -> {
            StsClientBuilder b = StsClient.builder()
                    .region(Region.of(r))
                    .credentialsProvider(chain.get())
                    .httpClientBuilder(ApacheHttpClient.builder()
                            .connectionTimeout(Duration.ofSeconds(5))
                            .socketTimeout(Duration.ofSeconds(10)))
                    .overrideConfiguration(o -> o.retryStrategy(AwsRetryStrategy.standardRetryStrategy().toBuilder()
                            .maxAttempts(3)
                            .build()));
            String endpoint = config.awsStsEndpointUrl();
            if (endpoint != null) {
                b.endpointOverride(URI.create(endpoint));
            }
            return b.build();
        });
    }

    /** The profile is checked before each fetch. */
    private Provider refreshing(
            Source source, Callable<Refreshing.Expiring<SigV4.Credentials>> fetch, Duration window) {
        return new Refreshing<>(vertx, window, () -> {
            try {
                verifyProfile(source);
                return fetch.call();
            } catch (CredentialsUnavailableException e) {
                throw e;
            } catch (Exception e) {
                throw new CredentialsUnavailableException(source, e);
            }
        })::get;
    }
}
