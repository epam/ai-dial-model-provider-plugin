package com.epam.aidial.provider.core;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpServerOptions;

public final class ModelProviderVerticle extends AbstractVerticle {

    private final HttpServerShim shim;
    private final HttpServerOptions options;

    public ModelProviderVerticle(HttpServerShim shim, HttpServerOptions options) {
        this.shim = shim;
        this.options = options;
    }

    /**
     * No idle timeout: Vert.x's would also close a connection waiting on a slow non-streamed answer; DIAL Core closes
     * its idle connections itself.
     */
    public static HttpServerOptions serverOptions(String host, int port) {
        return new HttpServerOptions()
                .setHost(host)
                .setPort(port)
                .setHttp2ClearTextEnabled(false)
                .setMaxHeaderSize(1 << 20) // X-Upstream-Extra-Data and X-Dial-Application-Properties outgrow 8 KB
                .setHandle100ContinueAutomatically(true)
                .setTcpKeepAlive(true);
    }

    @Override
    public void start(Promise<Void> started) {
        vertx.createHttpServer(options)
                .requestHandler(shim)
                .listen()
                .<Void>mapEmpty()
                .onComplete(started);
    }
}
