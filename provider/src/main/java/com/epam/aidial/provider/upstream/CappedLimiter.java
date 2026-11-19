package com.epam.aidial.provider.upstream;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicBoolean;

// Shared by every event loop: the lock guards a counter and a queue only, and a waiter is completed on its own context.
final class CappedLimiter implements Limiter {

    interface Timers {

        long start(long millis, Handler<Long> handler);

        void cancel(long id);
    }

    private final Vertx vertx;
    private final Timers timers;
    private final int max;
    // The wait for a free slot, resolved once: 0 without limit, below 0 not at all.
    private final long waitMillis;
    private final Deque<Waiter> waiters = new ArrayDeque<>();
    private int inFlight;

    CappedLimiter(Vertx vertx, int max, Limits.PoolWait poolWait) {
        this(vertx, max, poolWait, new Timers() {
            @Override
            public long start(long millis, Handler<Long> handler) {
                return vertx.setTimer(millis, handler);
            }

            @Override
            public void cancel(long id) {
                vertx.cancelTimer(id);
            }
        });
    }

    CappedLimiter(Vertx vertx, int max, Limits.PoolWait poolWait, Timers timers) {
        this.vertx = vertx;
        this.timers = timers;
        this.max = max;
        this.waitMillis = switch (poolWait) {
            case Limits.PoolWait.Unbounded() -> 0;
            case Limits.PoolWait.FailFast() -> -1;
            case Limits.PoolWait.Bounded b -> b.timeout().toMillis();
        };
    }

    private final class HeldSlot implements Slot {

        private final AtomicBoolean released = new AtomicBoolean();

        @Override
        public void release() {
            if (!released.compareAndSet(false, true)) {
                return;
            }
            Waiter next;
            synchronized (CappedLimiter.this) {
                next = waiters.pollFirst();
                if (next == null) {
                    inFlight--;
                }
            }
            if (next != null) {
                next.grant(new HeldSlot()); // the slot passes on: inFlight stays
            }
        }
    }

    private final class Waiter {

        private final Context context = vertx.getOrCreateContext();
        private final Promise<Slot> promise = Promise.promise();

        void grant(Slot slot) {
            context.runOnContext(v -> {
                if (!promise.tryComplete(slot)) {
                    slot.release(); // abandoned meanwhile: pass it on
                }
            });
        }

        void giveUp(Throwable cause) {
            boolean removed;
            synchronized (CappedLimiter.this) {
                removed = waiters.remove(this);
            }
            if (removed) {
                promise.tryFail(cause);
            }
        }
    }

    @Override
    public Future<Slot> acquire(Future<Void> aborted) {
        Waiter waiter;
        synchronized (this) {
            if (inFlight < max) {
                inFlight++;
                return Future.succeededFuture(new HeldSlot());
            }
            if (waitMillis < 0) {
                return Future.failedFuture(new PoolExhaustedException());
            }
            waiter = new Waiter();
            waiters.addLast(waiter);
        }
        if (waitMillis > 0) {
            long timer = timers.start(waitMillis, id -> waiter.giveUp(new PoolExhaustedException()));
            // However the wait ends, even before this line, the timer ends with it: a slot may be granted already.
            waiter.promise.future().onComplete(v -> timers.cancel(timer));
        }
        aborted.onComplete(v -> waiter.giveUp(new ClientGoneException()));
        return waiter.promise.future();
    }
}
