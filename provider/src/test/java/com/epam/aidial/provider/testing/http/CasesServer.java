package com.epam.aidial.provider.testing.http;

import static com.epam.aidial.provider.testing.MockUpstream.await;

import com.epam.aidial.provider.api.Handler;
import com.epam.aidial.provider.core.HttpServerShim;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicTest;

/**
 * The set-up every HTTP-case class shares: a Vert.x, the in-JVM server (behind {@link HttpServerShim}) that serves the
 * routes of the case being run, one {@link HttpTestRunner} per {@link Setup}, and the clients a case opened, closed
 * before the next case's: their idle connections would add up on the mocks.
 *
 * <p>A class creates one in {@code @BeforeAll}, closes it in {@code @AfterAll}, and gives {@link #runner} the code that
 * builds its routes from a case's {@code server} member.
 */
public final class CasesServer implements AutoCloseable {

    /** Builds the routes a case runs against; the clients it opens are handed to {@link CasesServer#own}. */
    @FunctionalInterface
    public interface Setup {
        Handler routes(JsonNode setup) throws Exception;
    }

    private final Vertx vertx = Vertx.vertx();
    private final HttpServer server;
    private final List<HttpTestRunner> runners = new ArrayList<>();
    private final List<Supplier<Future<Void>>> resources = new ArrayList<>();
    private volatile Handler routes;

    public CasesServer() throws Exception {
        server = await(vertx.createHttpServer()
                .requestHandler(new HttpServerShim(request -> routes.handle(request)))
                .listen(0, "127.0.0.1"));
    }

    public Vertx vertx() {
        return vertx;
    }

    /** A runner whose every case is first given the routes {@code setup} builds, on this server. */
    public HttpTestRunner runner(Setup setup) {
        HttpTestRunner runner = new HttpTestRunner(serverMember -> {
            closeResources();
            routes = setup.routes(serverMember);
            return URI.create("http://127.0.0.1:" + server.actualPort());
        });
        runners.add(runner);
        return runner;
    }

    /** Closes {@code resource} before the next case's set-up; a {@code null} (no DIAL Core client) is ignored. */
    public <T> @Nullable T own(@Nullable T resource, Function<T, Future<Void>> close) {
        if (resource != null) {
            resources.add(() -> close.apply(resource));
        }
        return resource;
    }

    /** The case's {@code server.env} as the environment {@code Config.from} reads. */
    public static Map<String, String> env(JsonNode setup) {
        Map<String, String> env = new HashMap<>();
        setup.path("env")
                .properties()
                .forEach(e -> env.put(e.getKey(), e.getValue().asText()));
        return env;
    }

    /** One container per suite, one test per case; {@code runnerOf} picks the runner by the suite's name. */
    public static Stream<DynamicContainer> containers(
            Map<String, List<HttpTestCase>> suites, Function<String, HttpTestRunner> runnerOf) {
        return suites.entrySet().stream()
                .map(suite -> DynamicContainer.dynamicContainer(
                        suite.getKey(),
                        suite.getValue().stream()
                                .map(c -> DynamicTest.dynamicTest(
                                        c.name(), c.file().toUri(), () -> runnerOf.apply(suite.getKey())
                                                .run(c)))));
    }

    private void closeResources() throws Exception {
        for (var close : resources) {
            await(close.get());
        }
        resources.clear();
    }

    @Override
    public void close() throws Exception {
        closeResources();
        runners.forEach(HttpTestRunner::close);
        await(vertx.close());
    }
}
