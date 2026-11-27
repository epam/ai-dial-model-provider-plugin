package com.epam.aidial.provider.testing;

import com.epam.aidial.provider.api.AdapterRequest;
import com.epam.aidial.provider.api.Handler;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;

/** Requests of the tests: of a request that can call no DIAL Core. */
public final class Requests {

    /** The core of a request that calls no DIAL Core back: calling it fails. */
    public static final Handler NO_CORE =
            request -> Future.failedFuture(new IllegalStateException("the request has no DIAL Core to call"));

    private Requests() {}

    public static AdapterRequest of(
            HttpMethod method, String path, String query, MultiMap headers, Buffer body, Future<Void> aborted) {
        return of(method, path, query, headers, body, aborted, NO_CORE);
    }

    public static AdapterRequest of(
            HttpMethod method,
            String path,
            String query,
            MultiMap headers,
            Buffer body,
            Future<Void> aborted,
            Handler core) {
        return new AdapterRequest(method, path, query, headers, body, aborted, core);
    }
}
