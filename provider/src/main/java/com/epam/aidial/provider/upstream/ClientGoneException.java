package com.epam.aidial.provider.upstream;

import io.vertx.core.Future;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/** Nothing is written or logged for it: the client is gone. */
public final class ClientGoneException extends RuntimeException {

    public ClientGoneException() {
        super("client gone", null, false, false);
    }

    /**
     * Whether a failure is the client's going: it is that, or the client has gone, whatever the failure was (the
     * client's reset is what breaks a call that it abandoned, so the call fails with some other error).
     *
     * @param failure null for none
     */
    public static boolean isGone(@Nullable Throwable failure, Future<Void> aborted) {
        return failure instanceof ClientGoneException || aborted.isComplete();
    }

    /** The failure, or the client's going when that is what broke the call. */
    public static Throwable of(Throwable failure, Future<Void> aborted) {
        return isGone(failure, aborted) ? new ClientGoneException() : failure;
    }

    /** A {@code recover} that leaves the client's going to fail the future, and recovers from any other failure. */
    public static <T> Function<Throwable, Future<T>> unlessGone(
            Future<Void> aborted, Function<Throwable, Future<T>> recover) {
        return failure ->
                isGone(failure, aborted) ? Future.failedFuture(new ClientGoneException()) : recover.apply(failure);
    }
}
