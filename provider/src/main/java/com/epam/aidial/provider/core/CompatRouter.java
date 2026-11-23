package com.epam.aidial.provider.core;

import com.epam.aidial.provider.api.AdapterRequest;
import com.epam.aidial.provider.api.AdapterResponse;
import com.epam.aidial.provider.api.ModelProvider;
import io.vertx.core.Future;
import io.vertx.core.http.HttpMethod;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Serves each of its mounts below its path prefix, which the mount does not see: {@code /compat/openai/x} reaches the
 * mount at {@code /compat/openai} as {@code /x}. Besides the mounts, {@code /health} is the process's own; anything
 * else is a 404.
 */
public final class CompatRouter implements ModelProvider {

    private final Map<String, ModelProvider> mounts;
    private final Supplier<Future<Void>> close;

    /**
     * @param mounts by path prefix: a path segment boundary follows it ({@code /compat/openai}); none may be below
     *     another
     */
    public CompatRouter(Map<String, ModelProvider> mounts) {
        this(mounts, Future::succeededFuture);
    }

    /**
     * @param mounts see {@link #CompatRouter(Map)}
     * @param close started by {@link #close}; completes when everything the mounts hold is released
     */
    public CompatRouter(Map<String, ModelProvider> mounts, Supplier<Future<Void>> close) {
        for (String prefix : mounts.keySet()) {
            if (!prefix.startsWith("/") || prefix.endsWith("/")) {
                throw new IllegalArgumentException("a mount's prefix is /segment[/segment...]: " + prefix);
            }
            if (mounts.keySet().stream().anyMatch(other -> !other.equals(prefix) && other.startsWith(prefix + "/"))) {
                throw new IllegalArgumentException("a mount is below another: " + prefix);
            }
        }
        this.mounts = Map.copyOf(mounts);
        this.close = close;
    }

    /** A request still in flight fails. The future fails if any part failed to close. */
    public Future<Void> close() {
        return close.get();
    }

    @Override
    public Future<AdapterResponse> handle(AdapterRequest request) {
        String path = request.path();
        if (path.equals("/health")) {
            return Future.succeededFuture(request.method() == HttpMethod.GET ? health() : methodNotAllowed("GET"));
        }
        for (Map.Entry<String, ModelProvider> mount : mounts.entrySet()) {
            String prefix = mount.getKey();
            if (path.startsWith(prefix + "/")) {
                return mount.getValue().handle(request.withPath(path.substring(prefix.length())));
            }
        }
        return Future.succeededFuture(notFound());
    }

    public static AdapterResponse notFound() {
        return AdapterResponse.json(404, "{\"detail\":\"Not Found\"}");
    }

    private static AdapterResponse health() {
        return AdapterResponse.json(200, "{\"status\":\"ok\"}");
    }

    static AdapterResponse methodNotAllowed(String allow) {
        AdapterResponse response = AdapterResponse.json(405, "{\"detail\":\"Method Not Allowed\"}");
        response.headers().set("Allow", allow);
        return response;
    }
}
