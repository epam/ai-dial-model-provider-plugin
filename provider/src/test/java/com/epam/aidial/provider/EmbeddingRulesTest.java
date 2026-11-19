package com.epam.aidial.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * An embedding process (ai-dial-core) owns its process: the provider module must never read the environment or
 * system-wide settings, or carry a logging backend or the OpenTelemetry SDK.
 */
class EmbeddingRulesTest {

    private static final List<String> FORBIDDEN = List.of(
            "System.getenv",
            "System.setProperty",
            "System.getProperty",
            "System.exit",
            "Runtime.getRuntime",
            "Integer.getInteger",
            "Long.getLong",
            "Boolean.getBoolean");

    @Test
    void noProcessWideSettings() throws IOException {
        try (Stream<Path> sources = Files.walk(Path.of("src/main/java"))) {
            List<String> violations = sources.filter(p -> p.toString().endsWith(".java"))
                    .flatMap(p -> FORBIDDEN.stream()
                            .filter(call -> read(p).contains(call))
                            .map(call -> p + ": " + call))
                    .toList();
            assertEquals(List.of(), violations);
        }
    }

    @Test
    void noLoggingBackendOrTelemetrySdk() {
        assertThrows(ClassNotFoundException.class, () -> Class.forName("ch.qos.logback.classic.Logger"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName("io.opentelemetry.sdk.OpenTelemetrySdk"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName("io.micrometer.core.instrument.MeterRegistry"));
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
