package com.epam.aidial.provider.credentials;

import com.epam.aidial.provider.core.Config;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.auth.http.HttpTransportFactory;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.ProxySelector;
import java.net.URISyntaxException;
import java.net.URL;
import java.time.Duration;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * The credentials are looked up on the first request and kept once a first token succeeded; a failed lookup is not
 * kept.
 */
public final class GoogleTokens implements GoogleTokenSource {

    static final String SCOPE = "https://www.googleapis.com/auth/cloud-platform";
    private static final Duration MARGIN = Duration.ofSeconds(10);

    /** quotaProject is null for none. */
    private record Source(GoogleCredentials credentials, String quotaProject) {

        Refreshing.Expiring<String> fetch() throws IOException {
            credentials.refreshIfExpired();
            AccessToken t = credentials.getAccessToken();
            // google-auth takes an answer without a token or an expiry; it is refused here.
            if (t == null
                    || t.getTokenValue() == null
                    || t.getTokenValue().isEmpty()
                    || t.getExpirationTime() == null) {
                throw new IOException("google: token endpoint answered no access token");
            }
            return Refreshing.Expiring.at(
                    t.getTokenValue(), t.getExpirationTime().toInstant());
        }
    }

    /** quotaProject goes out as x-goog-user-project; GOOGLE_CLOUD_QUOTA_PROJECT wins over the credentials' own. */
    public record Credential(Secret token, String quotaProject) {}

    private final Config config;
    private final UnaryOperator<URL> endpoints;
    private final Refreshing<Credential> credentials;
    private volatile Source source;

    public GoogleTokens(Vertx vertx, Config config) {
        this(vertx, config, UnaryOperator.identity());
    }

    /** The endpoints rewrite the URL of a credentials call: tests answer https endpoints with local fakes. */
    GoogleTokens(Vertx vertx, Config config, UnaryOperator<URL> endpoints) {
        this.config = config;
        this.endpoints = endpoints;
        this.credentials = new Refreshing<>(vertx, MARGIN, this::fetch);
    }

    /** Fails with the lookup's or the fetch's error, or ClientGoneException when the client goes. */
    @Override
    public Future<String> token(Future<Void> aborted) {
        return credential(aborted).map(c -> c.token().value());
    }

    /** Fails as {@link #token} does. */
    public Future<Credential> credential(Future<Void> aborted) {
        return credentials.get(aborted);
    }

    private Refreshing.Expiring<Credential> fetch() throws IOException {
        Source s = source != null ? source : lookUp();
        Refreshing.Expiring<String> fresh = s.fetch();
        source = s; // kept once a first token succeeded
        return new Refreshing.Expiring<>(
                new Credential(new Secret(fresh.value()), s.quotaProject()), fresh.expiresAt());
    }

    private Source lookUp() throws IOException {
        GoogleCredentials found;
        try {
            found = applicationDefault();
        } catch (IOException e) {
            throw new IOException("google: no Application Default Credentials: " + e.getMessage(), e);
        }
        GoogleCredentials scoped =
                AwsContainerCredentials.replacingSourceOf(found, config).createScoped(List.of(SCOPE));
        String setting = config.googleCloudQuotaProject();
        return new Source(scoped, setting != null ? setting : scoped.getQuotaProjectId());
    }

    private GoogleCredentials applicationDefault() throws IOException {
        // Token calls go through the environment's proxy.
        ProxySelector proxies = config.proxies().selector();
        NetHttpTransport transport = new NetHttpTransport.Builder()
                .setConnectionFactory(url -> {
                    URL target = endpoints.apply(url);
                    try {
                        return (HttpURLConnection) target.openConnection(
                                proxies.select(target.toURI()).getFirst());
                    } catch (URISyntaxException e) {
                        return (HttpURLConnection) target.openConnection();
                    }
                })
                .build();
        HttpTransportFactory factory = () -> transport;
        String file = config.googleApplicationCredentials();
        if (file == null) {
            return GoogleCredentials.getApplicationDefault(factory);
        }
        try (InputStream in = new FileInputStream(file)) {
            return GoogleCredentials.fromStream(in, factory);
        }
    }
}
