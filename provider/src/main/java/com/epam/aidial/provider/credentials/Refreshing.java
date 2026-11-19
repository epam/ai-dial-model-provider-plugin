package com.epam.aidial.provider.credentials;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;

/** A failed fetch is not kept: the next call fetches again. */
final class Refreshing<T> {

    /** expiresAt is null for a value that never expires. */
    record Expiring<T>(T value, @Nullable Instant expiresAt) {

        static <T> Expiring<T> at(T value, Instant expiresAt) {
            return new Expiring<>(value, expiresAt);
        }

        static <T> Expiring<T> never(T value) {
            return new Expiring<>(value, null);
        }

        boolean outlasts(Duration margin) {
            return expiresAt == null || Instant.now().plus(margin).isBefore(expiresAt);
        }

        @Override
        public String toString() {
            return "Expiring[expiresAt=" + expiresAt + "]"; // never the value: it is a credential
        }
    }

    private final Duration margin;
    private final Callable<Expiring<T>> fetch;
    private final SharedFetch<T> fetches;
    private volatile Expiring<T> kept;

    /**
     * @param margin a value with this or less left is not reused
     * @param fetch the blocking fetch, run on a worker thread
     */
    Refreshing(Vertx vertx, Duration margin, Callable<Expiring<T>> fetch) {
        this.margin = margin;
        this.fetch = fetch;
        this.fetches = new SharedFetch<>(vertx);
    }

    /** Fails with the fetch's error, or ClientGoneException when the client goes. */
    Future<T> get(Future<Void> aborted) {
        Expiring<T> k = kept;
        if (k != null && k.outlasts(margin)) {
            return Future.succeededFuture(k.value());
        }
        return fetches.fetch(
                "",
                () -> {
                    Expiring<T> fresh = fetch.call();
                    kept = fresh;
                    return fresh.value();
                },
                aborted);
    }
}
