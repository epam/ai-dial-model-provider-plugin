package com.epam.aidial.provider.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import com.epam.aidial.provider.api.AdapterResponse;
import com.epam.aidial.provider.core.HttpServerShim;
import com.epam.aidial.provider.core.ModelProviderVerticle;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.impl.VertxInternal;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MainTest {

    @Test
    void logLevelTakesWarningAndCritical() {
        assertEquals(Level.WARN, Main.logLevel("WARNING"));
        assertEquals(Level.WARN, Main.logLevel("warning"));
        assertEquals(Level.ERROR, Main.logLevel("CRITICAL"));
        assertEquals(Level.DEBUG, Main.logLevel("DEBUG"));
        assertEquals(Level.ERROR, Main.logLevel(" error "));
        assertEquals(Level.INFO, Main.logLevel("nonsense"));
    }

    @Test
    void tracingOffWithoutAnExporter() throws Exception {
        for (Map<String, String> env : List.<Map<String, String>>of(
                Map.of(), Map.of("OTEL_TRACES_EXPORTER", "none"), Map.of("OTEL_TRACES_EXPORTER", " "))) {
            assertNull(Main.openTelemetry(env), env.toString());
        }
        Vertx vertx = Main.vertx(null);
        try {
            assertNull(((VertxInternal) vertx).tracer());
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void tracingOnWithAnExporter() throws Exception {
        OpenTelemetrySdk openTelemetry = Main.openTelemetry(Map.of("OTEL_TRACES_EXPORTER", "console"));
        assertNotNull(openTelemetry);
        Vertx vertx = Main.vertx(openTelemetry);
        try {
            assertNotNull(((VertxInternal) vertx).tracer());
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            openTelemetry.close();
        }
    }

    /** System properties rank above the environment: they stand in for a deployment's variables. */
    @Test
    void deploymentTelemetryVariables() {
        Map<String, String> deployment = Map.of(
                "otel.metrics.exporter",
                "otlp,prometheus",
                "otel.logs.exporter",
                "otlp",
                "otel.exporter.otlp.endpoint",
                "http://127.0.0.1:4317");
        deployment.forEach(System::setProperty);
        try {
            OpenTelemetrySdk openTelemetry = Main.openTelemetry(Map.of("OTEL_TRACES_EXPORTER", "otlp"));
            String tracing = openTelemetry.getSdkTracerProvider().toString();
            openTelemetry.close();
            assertTrue(tracing.contains("OtlpGrpcSpanExporter"), tracing);
            System.setProperty("otel.exporter.otlp.protocol", "http/protobuf");
            openTelemetry = Main.openTelemetry(Map.of("OTEL_TRACES_EXPORTER", "otlp"));
            tracing = openTelemetry.getSdkTracerProvider().toString();
            openTelemetry.close();
            assertTrue(tracing.contains("OtlpHttpSpanExporter"), "a protocol variable still chooses: " + tracing);
        } finally {
            deployment.keySet().forEach(System::clearProperty);
            System.clearProperty("otel.exporter.otlp.protocol");
        }
    }

    @Test
    void serviceNameDefaultsOnlyWhenUnset() {
        AttributeKey<String> name = AttributeKey.stringKey("service.name");
        assertEquals(
                "ai-dial-model-provider",
                Main.withDefaultServiceName(Resource.getDefault()).getAttribute(name));
        Resource named = Resource.create(Attributes.of(name, "from-resource-attributes"));
        assertEquals(
                "from-resource-attributes", Main.withDefaultServiceName(named).getAttribute(name));
    }

    @Test
    void shutdownWaitsForTheRequestsInFlight() throws Exception {
        Vertx vertx = Vertx.vertx();
        Promise<AdapterResponse> answer = Promise.promise();
        HttpServerShim shim = new HttpServerShim(request -> answer.future());
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        vertx.deployVerticle(new ModelProviderVerticle(shim, ModelProviderVerticle.serverOptions("127.0.0.1", port)))
                .toCompletionStage()
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        CompletableFuture<HttpResponse<String>> response = HttpClient.newHttpClient()
                .sendAsync(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        while (shim.inFlight() == 0) {
            Thread.sleep(10);
        }

        CompletableFuture<Void> stopped = CompletableFuture.runAsync(() -> Main.shutdown(vertx, shim, null));
        Thread.sleep(300);
        assertTrue(!stopped.isDone(), "shutdown must wait for the request in flight");
        vertx.runOnContext(v -> answer.complete(AdapterResponse.json(200, "{\"late\":true}")));

        assertEquals("{\"late\":true}", response.get(10, TimeUnit.SECONDS).body());
        stopped.get(10, TimeUnit.SECONDS);
    }
}
