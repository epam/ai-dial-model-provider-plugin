package com.epam.aidial.provider.testing;

import java.time.Duration;
import java.util.function.BooleanSupplier;

public final class Await {

    private static final long POLL_MILLIS = 10;

    private Await() {}

    /** Polled every 10 ms. */
    public static boolean until(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                return false;
            }
            Thread.sleep(POLL_MILLIS);
        }
        return true;
    }
}
