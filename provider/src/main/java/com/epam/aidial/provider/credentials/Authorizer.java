package com.epam.aidial.provider.credentials;

import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpMethod;
import java.net.URI;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

@FunctionalInterface
public interface Authorizer {

    Authorizer NONE = (method, url, headers, payload) -> Future.succeededFuture();

    /**
     * A signature covers the headers and the payload as they are, so the headers are final before this is called.
     *
     * @param payload null for a request without a body
     */
    Future<Void> authorize(HttpMethod method, String url, MultiMap headers, @Nullable byte[] payload);

    static Authorizer header(String name, Secret key) {
        return headers(List.of(name), key);
    }

    /** An empty key sets no header at all. */
    static Authorizer headers(List<String> names, Secret key) {
        return (method, url, headers, payload) -> {
            if (!key.value().isEmpty()) {
                names.forEach(name -> headers.set(name, key.value()));
            }
            return Future.succeededFuture();
        };
    }

    static Authorizer bearer(String token) {
        return header("Authorization", new Secret("Bearer " + token));
    }

    static Authorizer bearer(Supplier<Future<String>> token) {
        return (method, url, headers, payload) -> token.get().map(value -> {
            headers.set("Authorization", "Bearer " + value);
            return null;
        });
    }

    /** {@code host} is signed but not set on the request: the HTTP client sets it itself. */
    static Authorizer sigV4(
            AwsCredentials.Provider provider, String region, String service, Future<Void> aborted, Clock clock) {
        return (method, url, headers, payload) -> provider.credentials(aborted).map(credentials -> {
            Map<String, String> toSign = new LinkedHashMap<>();
            headers.names().forEach(name -> toSign.put(name, String.join(",", headers.getAll(name))));
            SigV4.sign(
                            method.name(),
                            URI.create(url),
                            toSign,
                            payload == null ? new byte[0] : payload,
                            region,
                            service,
                            credentials,
                            clock.instant())
                    .forEach((name, value) -> {
                        if (!name.equals("host")) {
                            headers.set(name, value);
                        }
                    });
            return null;
        });
    }
}
