package com.epam.aidial.provider.upstream;

import io.vertx.core.Future;
import io.vertx.core.Vertx;

/** A call holds its slot from when it is sent until its body is read to the end or abandoned. */
interface Limiter {

    /** Releasing twice is harmless. */
    interface Slot {

        void release();
    }

    Limiter NONE = new Limiter() {

        private final Future<Slot> free = Future.succeededFuture(() -> {});

        @Override
        public Future<Slot> acquire(Future<Void> aborted) {
            return free;
        }
    };

    static Limiter of(Vertx vertx, Limits limits) {
        return limits.maxConnections().isPresent()
                ? new CappedLimiter(vertx, limits.maxConnections().getAsInt(), limits.poolWait())
                : NONE;
    }

    /** Fails with {@link PoolExhaustedException} when the wait ends, {@link ClientGoneException} if the client goes. */
    Future<Slot> acquire(Future<Void> aborted);
}
