package com.epam.aidial.provider.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * What the tests need to know about the machine: the cloud settings (the {@code .env} of the repository root, the
 * environment winning) and the Azure resource and key ({@code integration-tests/azure.local.json}). Both files are
 * git-ignored, and a missing one is named in the failure that needs it.
 */
final class Env {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path ROOT = root();
    private static final Map<String, String> SETTINGS = settings();

    private Env() {}

    /** The settings of the service: the .env file under the process environment, as the image gets them. */
    static Map<String, String> all() {
        return SETTINGS;
    }

    /** A setting the cloud's deployments are built from. */
    static String require(String name) {
        String value = SETTINGS.get(name);
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException(name + " is not set: see .env.example (integration-tests/README.md)");
        }
        return value;
    }

    /** The Azure resource (https://name.cognitiveservices.azure.com) and the key of its API. */
    record Azure(String resource, String key) {}

    static Azure azure() {
        Path file = ROOT.resolve("integration-tests/azure.local.json");
        if (!Files.exists(file)) {
            throw new IllegalStateException(file + " is missing: see integration-tests/azure.example.json");
        }
        try {
            JsonNode json = JSON.readTree(file.toFile());
            return new Azure(json.path("resource").asText(), json.path("key").asText());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path root() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("settings.gradle"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException(
                    "settings.gradle not found above " + Path.of("").toAbsolutePath());
        }
        return dir;
    }

    private static Map<String, String> settings() {
        Map<String, String> settings = new HashMap<>();
        Path file = ROOT.resolve(".env");
        if (Files.exists(file)) {
            try {
                for (String line : Files.readAllLines(file)) {
                    int eq = line.indexOf('=');
                    if (!line.isBlank() && !line.startsWith("#") && eq > 0) {
                        settings.put(
                                line.substring(0, eq).strip(),
                                unquote(line.substring(eq + 1).strip()));
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        settings.putAll(System.getenv());
        return Map.copyOf(settings);
    }

    private static String unquote(String value) {
        boolean quoted = value.length() >= 2
                && (value.startsWith("'") && value.endsWith("'") || value.startsWith("\"") && value.endsWith("\""));
        return quoted ? value.substring(1, value.length() - 1) : value;
    }
}
