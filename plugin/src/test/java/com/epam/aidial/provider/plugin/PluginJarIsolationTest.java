package com.epam.aidial.provider.plugin;

import static com.epam.aidial.provider.testing.MockUpstream.await;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.epam.aidial.core.plugin.Call;
import com.epam.aidial.core.plugin.HostDelegate;
import com.epam.aidial.core.plugin.Plugin;
import com.epam.aidial.core.plugin.PluginProvider;
import com.epam.aidial.core.plugin.Request;
import com.epam.aidial.core.plugin.Response;
import com.epam.aidial.provider.testing.MockUpstream;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The plugin jar as DIAL Core loads it: one class loader whose parent holds only what Core provides (the plugin API,
 * Vert.x, Netty, Jackson, OpenTelemetry, SLF4J) and which sees nothing of this module's class path. Whatever else the
 * plugin needs must be in the jar; a missing class is a {@link NoClassDefFoundError} here, not at the first request in
 * production.
 */
class PluginJarIsolationTest {

    /** What plugin/build.gradle leaves out of the jar; of Jackson, not the SDKs' extra modules, which the jar has. */
    private static final List<String> PROVIDED_BY_CORE = List.of(
            "io.vertx.",
            "io.netty.",
            "io.opentelemetry.",
            "org.slf4j.",
            "com.fasterxml.jackson.core.",
            "com.fasterxml.jackson.databind.",
            "com.fasterxml.jackson.annotation.",
            "com.epam.aidial.core.plugin.");

    private Vertx vertx;
    private MockUpstream mock;
    private URLClassLoader isolated;

    /** What Core's class loader holds, as far as the plugin is concerned: the libraries it provides, and the JDK. */
    private static final class CoreLike extends ClassLoader {

        private final ClassLoader provided;

        CoreLike(ClassLoader provided) {
            super(ClassLoader.getPlatformClassLoader());
            this.provided = provided;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (PROVIDED_BY_CORE.stream().anyMatch(name::startsWith)) {
                return provided.loadClass(name);
            }
            return super.loadClass(name, resolve);
        }
    }

    @BeforeEach
    void start() throws Exception {
        vertx = Vertx.vertx();
        mock = new MockUpstream(vertx)
                .reply("/", 200, Map.of("Content-Type", "application/json"), "{\"id\":\"msg_1\"}");
        URL jar = Path.of(System.getProperty("plugin.jar")).toUri().toURL();
        isolated = new URLClassLoader(new URL[] {jar}, new CoreLike(getClass().getClassLoader()));
    }

    @AfterEach
    void stop() throws Exception {
        isolated.close();
        await(vertx.close());
    }

    @Test
    void theJarCarriesEverythingCoreDoesNotProvide() throws Exception {
        assertSame(Vertx.class, isolated.loadClass("io.vertx.core.Vertx"), "Core's");
        assertNotSame(
                getClass().getClassLoader(),
                isolated.loadClass("com.epam.aidial.provider.ModelProviders").getClassLoader(),
                "this module's class path is not visible");
        assertEquals(
                isolated,
                isolated.loadClass("com.azure.identity.DefaultAzureCredentialBuilder")
                        .getClassLoader(),
                "a library of the plugin's own");
        assertEquals(
                isolated,
                isolated.loadClass("software.amazon.awssdk.regions.Region").getClassLoader(),
                "a library of the plugin's own");
    }

    @Test
    void theProviderIsFoundAndAnswersOneRequest() throws Exception {
        List<PluginProvider> found = ServiceLoader.load(PluginProvider.class, isolated).stream()
                .map(ServiceLoader.Provider::get)
                .toList();
        assertEquals(
                List.of("model-provider", "translator"),
                found.stream().map(PluginProvider::type).toList());
        for (PluginProvider each : found) {
            assertEquals(isolated, each.getClass().getClassLoader(), "from the jar");
        }
        PluginProvider provider = found.getFirst();

        ObjectNode settings = JsonNodeFactory.instance.objectNode().put("adapter", "openai");
        assertEquals(List.of(), provider.validate(settings));
        Plugin plugin = provider.create(settings, vertx);
        try {
            MultiMap headers = MultiMap.caseInsensitiveMultiMap()
                    .add("X-Upstream-Endpoint", mock.url() + "/v1/messages")
                    .add("X-Upstream-Key", "k");
            Request request = new Request(
                    HttpMethod.POST,
                    "/anthropic/v1/messages",
                    headers,
                    Buffer.buffer("{\"model\":\"m\",\"max_tokens\":5,\"messages\":[]}"),
                    null,
                    null);
            Response response = await(plugin.handle(request, new NoCalls()));
            assertEquals(200, response.statusCode());
            assertEquals("{\"id\":\"msg_1\"}", read(response));
        } finally {
            plugin.close();
        }
    }

    /**
     * The SDKs the translator uses read and write both APIs, with the Kotlin, JDK 8 and JSR-310 modules of their JSON
     * mapper from the jar (Core's Jackson has none of them). Through reflection: these are the jar's classes, not this
     * module's.
     */
    @Test
    void theSdksParseAndWriteBothApis() throws Exception {
        Class<?> anthropic = isolated.loadClass("com.epam.aidial.provider.translation.AnthropicJson");
        Object body = anthropic
                .getMethod("parseRequest", byte[].class)
                .invoke(null, "{\"model\":\"m\",\"max_tokens\":4,\"stream\":true,\"messages\":[]}".getBytes(UTF_8));
        Class<?> bodyType = isolated.loadClass("com.anthropic.models.beta.messages.MessageCreateParams$Body");
        assertEquals(true, anthropic.getMethod("stream", bodyType).invoke(null, body));
        Object stop = isolated.loadClass("com.anthropic.models.messages.RawContentBlockStopEvent")
                .getMethod("of", long.class)
                .invoke(null, 3L);
        String written = (String) anthropic.getMethod("write", Object.class).invoke(null, stop);
        assertTrue(written.contains("\"index\":3"), written);

        Class<?> responses = isolated.loadClass("com.epam.aidial.provider.translation.ResponsesJson");
        Object response = responses
                .getMethod("parseResponse", byte[].class)
                .invoke(null, "{\"id\":\"resp_1\",\"status\":\"completed\",\"output\":[]}".getBytes(UTF_8));
        assertEquals("resp_1", response.getClass().getMethod("id").invoke(response));
        Object builder = isolated.loadClass("com.openai.models.responses.ResponseCreateParams")
                .getMethod("builder")
                .invoke(null);
        builder.getClass().getMethod("model", String.class).invoke(builder, "gpt-5.5");
        builder.getClass().getMethod("input", String.class).invoke(builder, "hi");
        String request = (String) responses
                .getMethod("requestJson", builder.getClass(), boolean.class)
                .invoke(null, builder, true);
        assertTrue(request.contains("\"stream\":true") && request.contains("\"model\":\"gpt-5.5\""), request);
    }

    /** The whole body, read on a Vert.x context, as DIAL Core reads it. */
    private String read(Response response) throws Exception {
        CompletableFuture<String> read = new CompletableFuture<>();
        Buffer content = Buffer.buffer();
        vertx.getOrCreateContext().runOnContext(v -> response.body()
                .exceptionHandler(read::completeExceptionally)
                .endHandler(end -> read.complete(content.toString()))
                .handler(content::appendBuffer)
                .resume());
        return read.get(10, TimeUnit.SECONDS);
    }

    private static final class NoCalls implements HostDelegate {

        @Override
        public Future<Response> call(Call call) {
            return Future.failedFuture("no calls");
        }

        @Override
        public Future<Void> cancelled() {
            return Future.<Void>future(promise -> {}); // never
        }
    }
}
