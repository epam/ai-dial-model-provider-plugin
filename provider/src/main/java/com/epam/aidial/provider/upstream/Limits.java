package com.epam.aidial.provider.upstream;

import java.time.Duration;
import java.util.OptionalInt;

/**
 * @param maxConnections calls in flight at most, over all hosts; empty: no cap
 * @param readTimeout the wait for the response headers and for each body read; {@link Duration#ZERO}: none
 */
public record Limits(OptionalInt maxConnections, PoolWait poolWait, Duration readTimeout) {

    public sealed interface PoolWait {

        record Unbounded() implements PoolWait {}

        record FailFast() implements PoolWait {}

        record Bounded(Duration timeout) implements PoolWait {

            public Bounded {
                if (!timeout.isPositive()) {
                    throw new IllegalArgumentException("a bounded wait is positive");
                }
            }
        }
    }

    public static Limits uncapped(Duration readTimeout) {
        return new Limits(OptionalInt.empty(), new PoolWait.Unbounded(), readTimeout);
    }

    /** A {@code max} of 0 or less is no cap. */
    public static Limits capped(int max, PoolWait poolWait, Duration readTimeout) {
        return new Limits(max > 0 ? OptionalInt.of(max) : OptionalInt.empty(), poolWait, readTimeout);
    }
}
