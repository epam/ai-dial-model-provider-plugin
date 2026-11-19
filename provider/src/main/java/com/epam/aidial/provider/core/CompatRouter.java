package com.epam.aidial.provider.core;

import com.epam.aidial.provider.api.AdapterRequest;
import com.epam.aidial.provider.api.AdapterResponse;
import com.epam.aidial.provider.api.ModelProvider;
import io.vertx.core.Future;
import io.vertx.core.http.HttpMethod;
import java.util.Map;
import java.util.function.Supplier;

public final class CompatRouter implements ModelProvider {

    private static final String PREFIX = "/compat/";

    private final Map<String, ModelProvider> adapters;
    private final Supplier<Future<Void>> close;

    public CompatRouter(Map<String, ModelProvider> adapters) {
        this(adapters, Future::succeededFuture);
    }

    /**
     * @param close started by {@link #close}; completes when everything the adapters hold is released
     */
    public CompatRouter(Map<String, ModelProvider> adapters, Supplier<Future<Void>> close) {
        this.adapters = Map.copyOf(adapters);
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
        if (path.startsWith(PREFIX)) {
            int slash = path.indexOf('/', PREFIX.length());
            ModelProvider adapter = slash < 0 ? null : adapters.get(path.substring(PREFIX.length(), slash));
            if (adapter != null) {
                return adapter.handle(request.withPath(path.substring(slash)));
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
