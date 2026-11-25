package com.epam.aidial.provider.core;

import com.epam.aidial.provider.api.AdapterRequest;
import com.epam.aidial.provider.api.AdapterResponse;
import com.epam.aidial.provider.api.Handler;
import io.vertx.core.Future;
import io.vertx.core.http.HttpMethod;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The first route of the path and method wins. A path that only has routes of other methods gets a 405 whose {@code
 * Allow} is the method of the first route of the path.
 */
public final class Routes implements Handler {

    public interface Group {
        void register(Routes routes);
    }

    public interface ByIdHandler {
        Future<AdapterResponse> handle(AdapterRequest request, String id);
    }

    /** A route: {@code prefix} is the whole path of a literal one (no suffix), else the base and a slash. */
    private record Route(HttpMethod method, String prefix, @Nullable String suffix, ByIdHandler handler) {

        /** Empty for a literal route; null when the path is not the route's. */
        @Nullable
        String match(String path) {
            if (suffix == null) {
                return path.equals(prefix) ? "" : null;
            }
            if (path.length() <= prefix.length() + suffix.length()
                    || !path.startsWith(prefix)
                    || !path.endsWith(suffix)) {
                return null;
            }
            String id = path.substring(prefix.length(), path.length() - suffix.length());
            return id.indexOf('/') < 0 ? id : null;
        }
    }

    private final List<Route> routes = new ArrayList<>();

    public static Routes of(Group... groups) {
        Routes table = new Routes();
        for (Group group : groups) {
            group.register(table);
        }
        return table;
    }

    public Routes add(HttpMethod method, String path, Handler handler) {
        routes.add(new Route(method, path, null, (request, id) -> handler.handle(request)));
        return this;
    }

    public Routes addById(HttpMethod method, String base, String suffix, ByIdHandler handler) {
        routes.add(new Route(method, base + "/", suffix, handler));
        return this;
    }

    @Override
    public Future<AdapterResponse> handle(AdapterRequest request) {
        String path = decode(request.path());
        String allowed = null;
        for (Route route : routes) {
            String id = route.match(path);
            if (id == null) {
                continue;
            }
            if (route.method.equals(request.method())) {
                return route.handler.handle(request, id);
            }
            if (allowed == null) {
                allowed = route.method.name();
            }
        }
        if (allowed == null) {
            return Future.succeededFuture(CompatRouter.notFound());
        }
        return Future.succeededFuture(CompatRouter.methodNotAllowed(allowed));
    }

    /** {@code +} stays a plus, unlike form decoding; a path with a malformed escape is matched as it is. */
    static String decode(String path) {
        try {
            return URLDecoder.decode(path.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return path;
        }
    }
}
