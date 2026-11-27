package com.epam.aidial.provider.core;

import static com.epam.aidial.provider.core.HttpServerShimTest.await;
import static com.epam.aidial.provider.core.HttpServerShimTest.freePort;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.epam.aidial.provider.testing.Requests;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

@Isolated // its undeployed port must stay free: another class running alongside could take it
class EmbeddedSmokeTest {

    @Test
    void twoProcessesInOneJvm() throws Exception {
        Vertx host = Vertx.vertx();
        Vertx other = Vertx.vertx();
        try {
            int hostPort = freePort();
            int otherPort = freePort();
            String hostDeployment = deploy(host, hostPort);
            deploy(other, otherPort);
            for (int i = 0; i < 8; i++) {
                assertEquals("{\"status\":\"ok\"}", health(hostPort));
                assertEquals("{\"status\":\"ok\"}", health(otherPort));
            }

            await(host.undeploy(hostDeployment));
            assertThrows(SocketException.class, () -> health(hostPort));
            assertEquals("{\"status\":\"ok\"}", health(otherPort));
        } finally {
            await(host.close());
            await(other.close());
        }
    }

    private static String deploy(Vertx vertx, int port) throws Exception {
        HttpServerShim shim = new HttpServerShim(new CompatRouter(Map.of()), Requests.NO_CORE);
        return await(vertx.deployVerticle(
                () -> new ModelProviderVerticle(shim, ModelProviderVerticle.serverOptions("127.0.0.1", port)),
                new DeploymentOptions().setInstances(2)));
    }

    /** One GET /health on a connection of its own, so that the requests spread over the server instances. */
    private static String health(int port) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            socket.getOutputStream()
                    .write("GET /health HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n"
                            .getBytes(StandardCharsets.UTF_8));
            String raw = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(raw.startsWith("HTTP/1.1 200 OK\r\n"), raw);
            return raw.substring(raw.indexOf("\r\n\r\n") + 4);
        }
    }
}
