package com.epam.aidial.provider.integration;

import com.epam.aidial.provider.ModelProviders;
import com.epam.aidial.provider.core.Config;
import com.epam.aidial.provider.core.HttpServerShim;
import com.epam.aidial.provider.core.ModelProviderVerticle;
import io.vertx.core.Vertx;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.TimeUnit;

/**
 * The service under test: the provider module behind its own HTTP server in this JVM, configured as the standalone
 * process is (the settings of {@link Env}), and started once for every test. The cloud SDKs find their credentials
 * as they do in the process: Azure's {@code DefaultAzureCredential}, the AWS profile, Application Default Credentials.
 */
final class Service {

    private static final String URL = start();

    private Service() {}

    /** The base URL of the running service. */
    static String url() {
        return URL;
    }

    private static String start() {
        try {
            int port = freePort();
            Vertx vertx = Vertx.vertx();
            HttpServerShim shim = ModelProviders.server(vertx, Config.from(Env.all()));
            vertx.deployVerticle(
                            new ModelProviderVerticle(shim, ModelProviderVerticle.serverOptions("127.0.0.1", port)))
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get(30, TimeUnit.SECONDS);
            Runtime.getRuntime().addShutdownHook(new Thread(vertx::close));
            return "http://127.0.0.1:" + port;
        } catch (IOException
                | InterruptedException
                | java.util.concurrent.ExecutionException
                | java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException("the service did not start", e);
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
