package com.epam.aidial.provider.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MainProcessTest {

    @Test
    void invalidSettingsExitWithStatus1() throws Exception {
        Run run = start(Map.of("PORT", "x"));
        assertEquals(1, run.waitFor());
        String out = run.stdout();
        assertTrue(out.contains("\"message\":\"invalid settings: PORT must be an integer from 0 to 65535\""), out);
        assertTrue(out.contains("\"level\":\"ERROR\""), out);
    }

    @Test
    void bindFailureExitsWithStatus1() throws Exception {
        try (ServerSocket taken = new ServerSocket(0)) {
            Run run = start(Map.of("PORT", String.valueOf(taken.getLocalPort())));
            assertEquals(1, run.waitFor());
            assertTrue(
                    run.stdout().contains("\"message\":\"startup failed: port=" + taken.getLocalPort()), run.stdout());
        }
    }

    @Test
    void servesHealthAndLogsJson() throws Exception {
        int port = freePort();
        Run run = start(Map.of("PORT", String.valueOf(port)));
        try {
            assertEquals("{\"status\":\"ok\"}", get(port, "/health", Map.of()));
        } finally {
            run.stop();
        }
        assertTrue(run.stdout().contains("\"message\":\"listening: port=" + port + "\""), run.stdout());
    }

    @Test
    void logLevelTakesWarningAndCritical() throws Exception {
        int port = freePort();
        Run run = start(Map.of("PORT", String.valueOf(port), "LOG_LEVEL", "WARNING"));
        try {
            get(port, "/health", Map.of());
        } finally {
            run.stop();
        }
        assertFalse(run.stdout().contains("listening"), run.stdout());
    }

    @Test
    void serverSpanContinuesTheTrace() throws Exception {
        int port = freePort();
        Run run = start(Map.of("PORT", String.valueOf(port), "OTEL_TRACES_EXPORTER", "console"));
        try {
            get(
                    port,
                    "/compat/openai/x",
                    Map.of("traceparent", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"));
            long deadline = System.currentTimeMillis() + 5000;
            while (!run.stderr().contains("0af7651916cd43dd8448eb211c80319c")
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
        } finally {
            run.stop();
        }
        assertTrue(run.stderr().contains("0af7651916cd43dd8448eb211c80319c"), run.stderr());
        assertTrue(run.stderr().contains("SERVER"), run.stderr());
    }

    private record Run(Process process, Path out, Path err) {

        int waitFor() throws InterruptedException {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "the process did not exit");
            return process.exitValue();
        }

        void stop() throws InterruptedException {
            process.destroy(); // SIGTERM: the drain, then exit
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        }

        String stdout() throws IOException {
            return Files.readString(out);
        }

        String stderr() throws IOException {
            return Files.readString(err);
        }
    }

    private static Run start(Map<String, String> env) throws IOException {
        String java = ProcessHandle.current().info().command().orElse("java");
        ProcessBuilder builder =
                new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), Main.class.getName());
        builder.environment()
                .keySet()
                .removeIf(name -> name.startsWith("OTEL_") || name.equals("LOG_LEVEL") || name.equals("PORT"));
        builder.environment().putAll(env);
        File out = File.createTempFile("model-provider-", ".out");
        File err = File.createTempFile("model-provider-", ".err");
        out.deleteOnExit();
        err.deleteOnExit();
        return new Run(builder.redirectOutput(out).redirectError(err).start(), out.toPath(), err.toPath());
    }

    private static String get(int port, String path, Map<String, String> headers) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        headers.forEach(request::header);
        long deadline = System.currentTimeMillis() + 20_000;
        while (true) {
            try {
                return client.send(request.build(), HttpResponse.BodyHandlers.ofString())
                        .body();
            } catch (IOException e) {
                if (System.currentTimeMillis() > deadline) {
                    throw e;
                }
                Thread.sleep(100);
            }
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
