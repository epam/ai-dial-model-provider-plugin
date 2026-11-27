package com.epam.aidial.provider.core;

import com.epam.aidial.provider.api.AdapterRequest;
import com.epam.aidial.provider.api.AdapterResponse;
import com.epam.aidial.provider.api.Body;
import com.epam.aidial.provider.api.RequestScope;
import com.epam.aidial.provider.upstream.ClientGoneException;
import com.epam.aidial.provider.upstream.UpstreamClient;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The calls this process makes to DIAL Core, as GET requests of a path and an API key. They go through the
 * {@link AdapterRequest#core() core} of the request they are made for: over HTTP at DIAL_URL for a service, through the
 * host for a plugin (which needs no DIAL_URL).
 */
public final class DialApi {

    /** The path of the user info of the caller of an API key. */
    public static final String USER_INFO = "/v1/user/info";

    private static final int ATTEMPTS = 3;

    private final Vertx vertx;

    public DialApi(Vertx vertx) {
        this.vertx = vertx;
    }

    public enum Retry {
        TRANSPORT,
        TRANSPORT_AND_STATUS
    }

    public static final class DownloadException extends RuntimeException {

        DownloadException(int status) {
            super("DIAL answered " + status, null, false, false);
        }

        DownloadException(Throwable cause) {
            super(cause.getMessage(), cause, false, false);
        }
    }

    /**
     * Fails with a {@link DownloadException} (the last status or transport error), or with ClientGoneException once
     * {@code aborted} completes.
     *
     * @param scope the request the call is made for: its core makes the call, and its client going stops it
     * @param path  starts with {@code /}
     */
    public Future<Buffer> get(RequestScope scope, String path, String key, Retry retry) {
        return attempt(scope, path, key, retry, 1).compose(answer -> switch (answer) {
            case Answer.Response r ->
                UpstreamClient.UpstreamResponse.isSuccess(r.status())
                        ? Future.succeededFuture(r.body())
                        : Future.<Buffer>failedFuture(new DownloadException(r.status()));
            case Answer.Failed f -> Future.<Buffer>failedFuture(new DownloadException(f.error()));
        });
    }

    /** Resolves with the last answer, a 2xx or not, or the last failure as an {@code Answer.Failed}. */
    private Future<Answer> attempt(RequestScope scope, String path, String key, Retry retry, int n) {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap().set("Api-Key", key);
        Future<Void> aborted = scope.aborted();
        AdapterRequest request =
                new AdapterRequest(HttpMethod.GET, path, null, headers, Buffer.buffer(), aborted, scope.core());
        return Answers.call(scope.core(), request)
                .compose(Bodies::whole)
                .map(response -> (Answer) new Answer.Response(response.status(), body(response)))
                .recover(e -> Future.succeededFuture(new Answer.Failed(e)))
                .compose(answer -> {
                    Throwable error = answer instanceof Answer.Failed f ? f.error() : null;
                    if (ClientGoneException.isGone(error, aborted)) {
                        return Future.failedFuture(new ClientGoneException());
                    }
                    if (!retried(answer, retry) || n == ATTEMPTS) {
                        return Future.succeededFuture(answer);
                    }
                    Promise<Answer> next = Promise.promise();
                    vertx.setTimer(backoffMillis(n), t -> attempt(scope, path, key, retry, n + 1)
                            .onComplete(next));
                    return next.future();
                });
    }

    /** {@link Bodies#whole} has read it. */
    private static Buffer body(AdapterResponse response) {
        return ((Body.Bytes) response.body()).buffer();
    }

    private static boolean retried(Answer answer, Retry retry) {
        if (answer instanceof Answer.Response r) {
            return retry == Retry.TRANSPORT_AND_STATUS && (r.status() == 408 || r.status() == 409 || r.status() == 429);
        }
        return true;
    }

    /** 0.5 s * 2^n, jittered by up to 0.5 s either way, at least 1 ms. */
    private static long backoffMillis(int n) {
        long millis =
                (long) (((1 << n) / 2.0 - 0.5 + ThreadLocalRandom.current().nextDouble()) * 1000);
        return Math.max(1, millis);
    }

    private sealed interface Answer {

        record Response(int status, Buffer body) implements Answer {}

        record Failed(Throwable error) implements Answer {}
    }
}
