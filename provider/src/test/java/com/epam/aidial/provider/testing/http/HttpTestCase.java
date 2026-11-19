package com.epam.aidial.provider.testing.http;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

public record HttpTestCase(String name, Path file, int document, JsonNode content) {

    @Override
    public String toString() {
        return name + " (" + file.getFileName() + " #" + document + ")";
    }

    public static List<HttpTestCase> load(String resourceDirectory) {
        URL url = HttpTestCase.class.getClassLoader().getResource(resourceDirectory);
        if (url == null) {
            throw new IllegalStateException(resourceDirectory + " is not on the test classpath: the build compiles it"
                    + " (./gradlew :provider:compileHttpTestCases)");
        }
        try (Stream<Path> files = Files.walk(Path.of(url.toURI()))) {
            List<HttpTestCase> cases = new ArrayList<>();
            for (Path file :
                    files.filter(p -> p.toString().endsWith(".yaml")).sorted().toList()) {
                cases.addAll(read(file));
            }
            return cases;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Plain YAML, read with SnakeYAML's safe constructor. */
    public static List<HttpTestCase> read(Path file) throws IOException {
        List<HttpTestCase> cases = new ArrayList<>();
        int document = 0;
        for (Object doc : new Yaml(new SafeConstructor(new LoaderOptions())).loadAll(Files.readString(file))) {
            JsonNode content = JsonMatch.JSON.valueToTree(doc);
            cases.add(new HttpTestCase(content.path("name").asText(), file, ++document, content));
        }
        return cases;
    }
}
