package com.epam.aidial.provider.core;

import static com.epam.aidial.provider.core.HttpServerShimTest.await;
import static com.epam.aidial.provider.core.HttpServerShimTest.freePort;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.epam.aidial.provider.api.AdapterResponse;
import com.epam.aidial.provider.testing.Requests;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerOptions;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ModelProviderVerticleTest {

    private Vertx vertx;
    private int port;
    private final AtomicReference<String> extraData = new AtomicReference<>();

    @BeforeEach
    void start() throws Exception {
        vertx = Vertx.vertx();
        port = freePort();
        HttpServerShim shim = new HttpServerShim(
                request -> {
                    extraData.set(request.headers().get("X-Upstream-Extra-Data"));
                    return Future.succeededFuture(AdapterResponse.json(200, "{}"));
                },
                Requests.NO_CORE);
        await(vertx.deployVerticle(
                new ModelProviderVerticle(shim, ModelProviderVerticle.serverOptions("127.0.0.1", port))));
    }

    @AfterEach
    void stop() throws Exception {
        await(vertx.close());
    }

    @Test
    void http11Only() throws Exception {
        // The JDK client offers h2c by Upgrade on a cleartext URI; the server keeps HTTP/1.1.
        HttpClient client =
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).build();
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(HttpClient.Version.HTTP_1_1, response.version());
        assertEquals(200, response.statusCode());
    }

    @Test
    void largeHeadersAreAccepted() throws Exception {
        String big = "{\"headers_to_proxy\":[\"" + "a".repeat(200_000) + "\"]}";
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/"))
                                .header("X-Upstream-Extra-Data", big)
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals(big, extraData.get());
    }

    @Test
    void clientConnectionsStayOpenWithTcpKeepAlive() {
        HttpServerOptions options = ModelProviderVerticle.serverOptions("127.0.0.1", 5000);
        assertEquals(0, options.getIdleTimeout(), "no idle timeout: Vert.x's would cut a slow non-streamed answer");
        assertTrue(options.isTcpKeepAlive());
    }
}
