package com.epam.aidial.provider.testing;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/** Each request is kept, then answered by the handler of the longest matching path prefix. */
public final class MockUpstream {

    public record Seen(String method, String uri, String path, MultiMap headers, String body) {}

    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private final Map<String, Handler<HttpServerRequest>> handlers = new ConcurrentHashMap<>();
    private final HttpServer server;

    public MockUpstream(Vertx vertx) throws Exception {
        server = await(vertx.createHttpServer()
                .requestHandler(request -> request.body().onSuccess(body -> {
                    seen.add(new Seen(
                            request.method().name(),
                            request.uri(),
                            request.path(),
                            MultiMap.caseInsensitiveMultiMap().addAll(request.headers()),
                            body.toString()));
                    handlers.entrySet().stream()
                            .filter(e -> request.path().startsWith(e.getKey()))
                            .max(Comparator.comparingInt(e -> e.getKey().length()))
                            .map(Map.Entry::getValue)
                            .orElse(r -> r.response().setStatusCode(599).end("no handler"))
                            .handle(request);
                }))
                .listen(0, "127.0.0.1"));
    }

    public MockUpstream on(String prefix, Handler<HttpServerRequest> handler) {
        handlers.put(prefix, handler);
        return this;
    }

    public MockUpstream reply(String prefix, int status, Map<String, String> headers, String body) {
        return on(prefix, request -> {
            headers.forEach(request.response()::putHeader);
            request.response().setStatusCode(status).end(body);
        });
    }

    public String url() {
        return "http://127.0.0.1:" + server.actualPort();
    }

    public List<Seen> seen() {
        return seen;
    }

    public List<Seen> seen(String prefix) {
        return seen.stream().filter(s -> s.path().startsWith(prefix)).toList();
    }

    public Seen last() {
        return seen.getLast();
    }

    public static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
    }
}
