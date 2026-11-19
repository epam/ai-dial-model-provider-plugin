package com.epam.aidial.provider.core;

import com.epam.aidial.provider.upstream.ClientGoneException;
import com.epam.aidial.provider.upstream.Limits;
import com.epam.aidial.provider.upstream.UpstreamClient;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/** Outside the mounts' connection caps; every body read is bounded by a 600 s read timeout. */
public final class DialApi {

    private static final int ATTEMPTS = 3;
    private final Vertx vertx;
    private final UpstreamClient client;
    private final String base;

    /** Null when DIAL_URL is unset. */
    public static DialApi from(Vertx vertx, Config config) {
        String url = config.dialUrl();
        return url == null ? null : new DialApi(vertx, config, url);
    }

    private DialApi(Vertx vertx, Config config, String url) {
        this(vertx, config, url, 600_000);
    }

    /** For tests: another read timeout. */
    DialApi(Vertx vertx, Config config, String url, long readTimeoutMillis) {
        this.vertx = vertx;
        this.client =
                new UpstreamClient(vertx, config.proxies(), Limits.uncapped(Duration.ofMillis(readTimeoutMillis)));
        this.base = url;
    }

    public Future<Void> close() {
        return client.close();
    }

    public String userInfoUrl() {
        return base + "v1/user/info";
    }

    public static final class DownloadException extends RuntimeException {

        DownloadException(int status) {
            super("DIAL answered " + status, null, false, false);
        }

        DownloadException(Throwable cause) {
            super(cause.getMessage(), cause, false, false);
        }
    }

    public enum Retry {
        TRANSPORT,
        TRANSPORT_AND_STATUS
    }

    /**
     * Fails with a DownloadException (the last status or transport error), with ClientGoneException once {@code
     * aborted} completes, or with IllegalArgumentException for an invalid URL.
     */
    public Future<Buffer> get(String url, String key, Retry retry, Future<Void> aborted) {
        return attempt(url, key, retry, aborted, 1).compose(answer -> switch (answer) {
            case Answer.Response r ->
                UpstreamClient.UpstreamResponse.isSuccess(r.status())
                        ? Future.succeededFuture(r.body())
                        : Future.<Buffer>failedFuture(new DownloadException(r.status()));
            case Answer.Failed f -> Future.<Buffer>failedFuture(new DownloadException(f.error()));
        });
    }

    /** Resolves with the last answer, a 2xx or not, or the last transport failure as an {@code Answer.Failed}. */
    private Future<Answer> attempt(String url, String key, Retry retry, Future<Void> aborted, int n) {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap().set("Api-Key", key);
        return client.send(HttpMethod.GET, url, headers, null, aborted)
                .compose(
                        response -> response.body().<Answer>map(b -> new Answer.Response(response.status(), b)),
                        e -> Future.succeededFuture(new Answer.Failed(e)))
                .compose(answer -> {
                    Throwable error = answer instanceof Answer.Failed f ? f.error() : null;
                    if (error instanceof ClientGoneException || aborted.isComplete()) {
                        return Future.failedFuture(new ClientGoneException());
                    }
                    if (error instanceof IllegalArgumentException) {
                        return Future.failedFuture(error); // an invalid URL, found before any request
                    }
                    if (!retried(answer, retry) || n == ATTEMPTS) {
                        return Future.succeededFuture(answer);
                    }
                    Promise<Answer> next = Promise.promise();
                    vertx.setTimer(backoffMillis(n), t -> attempt(url, key, retry, aborted, n + 1)
                            .onComplete(next));
                    return next.future();
                });
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
