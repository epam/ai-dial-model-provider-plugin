package com.epam.aidial.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * A package-to-package edge the code no longer has fails too, so that {@link #ALLOWED} shrinks as the shared helpers
 * move to their own packages. {@code (root)} is {@code com.epam.aidial.provider} itself.
 */
class LayeringTest {

    private static final String ALLOWED =
            """
            (root) -> anthropic, core, credentials, openai, translation, upstream
            anthropic -> api, core, credentials, json, upstream
            core -> api, upstream
            json -> api
            credentials -> core, json, upstream
            openai -> api, core, credentials, json, upstream
            translation -> anthropic, api, core, json, upstream
            upstream -> api
            """;

    private static final String ROOT = "com.epam.aidial.provider";
    private static final Pattern PACKAGE =
            Pattern.compile("^package " + Pattern.quote(ROOT) + "((?:\\.\\w+)*);", Pattern.MULTILINE);
    private static final Pattern REFERENCE =
            Pattern.compile(Pattern.quote(ROOT) + "((?:\\.[a-z]\\w*)*)\\.(?:[A-Z]|\\*)");

    @Test
    void packageEdgesAreTheAllowedOnes() throws IOException {
        Set<String> allowed = ALLOWED.lines()
                .flatMap(line -> {
                    String[] fromTo = line.split(" -> ");
                    return Arrays.stream(fromTo[1].split(", ")).map(to -> fromTo[0] + " -> " + to);
                })
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> actual;
        try (Stream<Path> sources = Files.walk(Path.of("src/main/java"))) {
            actual = sources.filter(p -> p.toString().endsWith(".java"))
                    .flatMap(LayeringTest::edges)
                    .collect(Collectors.toCollection(TreeSet::new));
        }
        assertEquals(List.of(), minus(actual, allowed), "edges not in ALLOWED");
        assertEquals(List.of(), minus(allowed, actual), "edges in ALLOWED the code no longer has");
    }

    private static Stream<String> edges(Path source) {
        String text;
        try {
            text = Files.readString(source);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Matcher declared = PACKAGE.matcher(text);
        if (!declared.find()) {
            throw new IllegalStateException(source + ": no package declaration under " + ROOT);
        }
        String from = name(declared.group(1));
        return REFERENCE
                .matcher(text)
                .results()
                .map(m -> name(m.group(1)))
                .filter(to -> !to.equals(from))
                .map(to -> from + " -> " + to);
    }

    private static String name(String suffix) {
        return suffix.isEmpty() ? "(root)" : suffix.substring(1);
    }

    private static List<String> minus(Set<String> a, Set<String> b) {
        return a.stream().filter(e -> !b.contains(e)).toList();
    }
}
