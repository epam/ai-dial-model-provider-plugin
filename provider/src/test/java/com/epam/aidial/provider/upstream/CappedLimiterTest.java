package com.epam.aidial.provider.upstream;

import static com.epam.aidial.provider.testing.MockUpstream.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.epam.aidial.provider.testing.Await;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CappedLimiterTest {

    private static final Future<Void> NEVER = Promise.<Void>promise().future();
    private static final Duration WIDE = Duration.ofSeconds(10);

    private Vertx vertx;

    @BeforeEach
    void start() {
        vertx = Vertx.vertx();
    }

    @AfterEach
    void stop() throws Exception {
        await(vertx.close());
    }

    /** Timers that are never fired; they record what was started and cancelled. */
    private static final class RecordedTimers implements CappedLimiter.Timers {

        private final AtomicLong ids = new AtomicLong();
        private final List<Long> started = new CopyOnWriteArrayList<>();
        private final List<Long> cancelled = new CopyOnWriteArrayList<>();
        private volatile Runnable beforeStart = () -> {};

        @Override
        public long start(long millis, Handler<Long> handler) {
            beforeStart.run();
            long id = ids.incrementAndGet();
            started.add(id);
            return id;
        }

        @Override
        public void cancel(long id) {
            cancelled.add(id);
        }
    }

    private CappedLimiter limiter(RecordedTimers timers) {
        return new CappedLimiter(vertx, 1, new Limits.PoolWait.Bounded(WIDE), timers);
    }

    @Test
    void timerCancelledWhenTheWaiterIsGranted() throws Exception {
        var timers = new RecordedTimers();
        CappedLimiter limiter = limiter(timers);
        Limiter.Slot first = await(limiter.acquire(NEVER));
        Future<Limiter.Slot> second = limiter.acquire(NEVER);
        assertEquals(List.of(1L), timers.started);
        first.release();
        await(second);
        assertTrue(Await.until(() -> timers.cancelled.equals(List.of(1L)), WIDE), "the wait's timer is cancelled");
    }

    @Test
    void timerCancelledWhenTheSlotIsGrantedBeforeItIsArmed() throws Exception {
        var timers = new RecordedTimers();
        CappedLimiter limiter = limiter(timers);
        Limiter.Slot first = await(limiter.acquire(NEVER));
        // The slot frees between the waiter being queued and its timer being armed, and the grant is made on the
        // waiter's context meanwhile: the wait of this thread gives that grant the time to complete first.
        timers.beforeStart = () -> {
            first.release();
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        Future<Limiter.Slot> second = limiter.acquire(NEVER);
        await(second);
        assertTrue(Await.until(() -> timers.cancelled.equals(List.of(1L)), WIDE), "the wait's timer is cancelled");
    }
}
