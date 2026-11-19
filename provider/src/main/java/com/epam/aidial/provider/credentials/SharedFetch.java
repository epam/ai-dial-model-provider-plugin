package com.epam.aidial.provider.credentials;

import com.epam.aidial.provider.upstream.ClientGoneException;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Each caller gets the result on its own context and stops waiting when its own client goes, while the fetch goes on
 * for the others. Nothing is cached: the next call after the result fetches again.
 */
final class SharedFetch<T> {

    private final Vertx vertx;
    private final ConcurrentHashMap<String, Future<T>> inFlight = new ConcurrentHashMap<>();

    SharedFetch(Vertx vertx) {
        this.vertx = vertx;
    }

    Future<T> fetch(String key, Callable<T> blocking, Future<Void> aborted) {
        if (aborted.isComplete()) {
            return Future.failedFuture(new ClientGoneException());
        }
        Context caller = vertx.getOrCreateContext();
        Promise<T> fetched = Promise.promise();
        Future<T> shared = inFlight.putIfAbsent(key, fetched.future());
        if (shared == null) {
            // Started outside the map's update: a fetch that ends at once must not remove its key within it.
            shared = fetched.future();
            Future<T> started = shared;
            vertx.executeBlocking(blocking, false).onComplete(ar -> {
                inFlight.remove(key, started);
                fetched.handle(ar);
            });
        }
        Promise<T> mine = Promise.promise();
        shared.onComplete(ar -> caller.runOnContext(v -> {
            if (ar.succeeded()) {
                mine.tryComplete(ar.result());
            } else {
                mine.tryFail(ar.cause());
            }
        }));
        aborted.onComplete(v -> caller.runOnContext(x -> mine.tryFail(new ClientGoneException())));
        return mine.future();
    }
}
