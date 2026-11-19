package com.epam.aidial.provider.app;

import ch.qos.logback.classic.Level;
import com.epam.aidial.provider.ModelProviders;
import com.epam.aidial.provider.api.ConfigException;
import com.epam.aidial.provider.core.Config;
import com.epam.aidial.provider.core.HttpServerShim;
import com.epam.aidial.provider.core.ModelProviderVerticle;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.tracing.TracingOptions;
import io.vertx.tracing.opentelemetry.OpenTelemetryTracingFactory;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    private static final AttributeKey<String> SERVICE_NAME = AttributeKey.stringKey("service.name");

    /** Within Kubernetes' default termination grace period of 30 s. */
    private static final long DRAIN_MILLIS = 25_000;

    private Main() {}

    public static void main(String[] args) throws Exception {
        Map<String, String> env = System.getenv();
        setLogLevel(env.getOrDefault("LOG_LEVEL", "INFO"));
        Config config;
        try {
            config = Config.from(env);
        } catch (ConfigException e) {
            log.error("invalid settings: {}", e.getMessage());
            System.exit(1);
            return;
        }

        OpenTelemetrySdk openTelemetry = openTelemetry(env);
        Vertx vertx = vertx(openTelemetry);
        HttpServerShim shim;
        try {
            shim = new HttpServerShim(ModelProviders.create(vertx, config));
        } catch (ConfigException e) {
            log.error("invalid settings: {}", e.getMessage());
            vertx.close();
            System.exit(1);
            return;
        }
        HttpServerOptions server = ModelProviderVerticle.serverOptions("0.0.0.0", config.port());
        try {
            vertx.deployVerticle(
                            () -> new ModelProviderVerticle(shim, server),
                            new DeploymentOptions().setInstances(VertxOptions.DEFAULT_EVENT_LOOP_POOL_SIZE))
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get();
        } catch (Exception e) {
            log.error("startup failed: port={}", config.port(), e);
            vertx.close();
            System.exit(1);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> shutdown(vertx, shim, openTelemetry)));
        log.info("listening: port={}", config.port());
    }

    /** The tracing SDK closes last, so that the spans of the drained requests are exported. */
    static void shutdown(Vertx vertx, HttpServerShim shim, OpenTelemetrySdk openTelemetry) {
        shim.drain();
        long deadline = System.currentTimeMillis() + DRAIN_MILLIS;
        while (shim.inFlight() > 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        try {
            vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("shutdown incomplete: {}", e.toString());
        }
        if (openTelemetry != null) {
            openTelemetry.close();
        }
    }

    /**
     * Null without OTEL_TRACES_EXPORTER. The metrics and logs exporters stay off whatever the variables say: the
     * service has neither to export.
     */
    static OpenTelemetrySdk openTelemetry(Map<String, String> env) {
        String exporter = env.get("OTEL_TRACES_EXPORTER");
        if (exporter == null || exporter.isBlank() || exporter.equals("none")) {
            return null;
        }
        return AutoConfiguredOpenTelemetrySdk.builder()
                .addPropertiesSupplier(
                        () -> Map.of("otel.traces.exporter", exporter, "otel.exporter.otlp.protocol", "grpc"))
                // A customizer, not a default: the variables rank above the supplier's defaults.
                .addPropertiesCustomizer(config -> Map.of(
                        "otel.metrics.exporter", "none",
                        "otel.logs.exporter", "none"))
                .addResourceCustomizer((resource, config) -> withDefaultServiceName(resource))
                .disableShutdownHook() // closed after the drain (shutdown)
                .build()
                .getOpenTelemetrySdk();
    }

    /** vertx-opentelemetry is the only context storage provider. */
    static Vertx vertx(OpenTelemetrySdk openTelemetry) {
        if (openTelemetry == null) {
            return Vertx.vertx();
        }
        return Vertx.builder()
                .with(new VertxOptions().setTracingOptions(new TracingOptions()))
                .withTracer(new OpenTelemetryTracingFactory(openTelemetry))
                .build();
    }

    /**
     * Only when neither OTEL_SERVICE_NAME nor OTEL_RESOURCE_ATTRIBUTES gives one: a default property would override
     * them.
     */
    static Resource withDefaultServiceName(Resource resource) {
        String name = resource.getAttribute(SERVICE_NAME);
        if (name != null && !name.startsWith("unknown_service")) {
            return resource;
        }
        return resource.merge(Resource.create(Attributes.of(SERVICE_NAME, "ai-dial-model-provider")));
    }

    /** Only this service's loggers: the rest stays at WARN. */
    private static void setLogLevel(String name) {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger("com.epam.aidial.provider")).setLevel(logLevel(name));
    }

    static Level logLevel(String name) {
        return switch (name.trim().toUpperCase(Locale.ROOT)) {
            case "WARNING" -> Level.WARN;
            case "CRITICAL", "FATAL" -> Level.ERROR;
            default -> Level.toLevel(name.trim(), Level.INFO);
        };
    }
}
