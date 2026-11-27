package com.epam.aidial.provider.plugin;

import com.epam.aidial.core.plugin.Call;
import com.epam.aidial.core.plugin.HostDelegate;
import com.epam.aidial.core.plugin.Plugin;
import com.epam.aidial.core.plugin.Request;
import com.epam.aidial.core.plugin.Response;
import com.epam.aidial.provider.api.AdapterRequest;
import com.epam.aidial.provider.api.AdapterResponse;
import com.epam.aidial.provider.api.Body;
import com.epam.aidial.provider.api.Handler;
import com.epam.aidial.provider.core.Answers;
import com.epam.aidial.provider.core.Buffers;
import com.epam.aidial.provider.core.CompatRouter;
import com.epam.aidial.provider.core.HttpServerShim;
import com.epam.aidial.provider.upstream.ClientGoneException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.Pipe;
import io.vertx.core.streams.ReadStream;
import java.io.IOException;

/**
 * DIAL Core's request becomes the request the standalone server makes of the same request below
 * {@code /compat/<adapter>}, and the answer what that server would write ({@link HttpServerShim}).
 */
final class ModelProviderPlugin implements Plugin {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Vertx vertx;
    private final String prefix;
    private final CompatRouter router;

    ModelProviderPlugin(Vertx vertx, String prefix, CompatRouter router) {
        this.vertx = vertx;
        this.prefix = prefix;
        this.router = router;
    }

    @Override
    public Future<Response> handle(Request request, HostDelegate host) {
        Context context = vertx.getOrCreateContext();
        Promise<Void> aborted = Promise.promise();
        host.cancelled().onComplete(v -> aborted.tryComplete());
        String uri = request.uri();
        int query = uri.indexOf('?');
        AdapterRequest adapterRequest = new AdapterRequest(
                request.method(),
                prefix + (query < 0 ? uri : uri.substring(0, query)),
                query < 0 ? null : uri.substring(query + 1),
                request.headers(),
                request.body(),
                aborted.future(),
                coreCall(host, aborted.future()));
        return Answers.call(router, adapterRequest)
                // DIAL Core stopped waiting: a failed answer ends its request quietly, as the standalone server writes
                // nothing to a client that has gone
                .recover(ClientGoneException.unlessGone(
                        aborted.future(), e -> Future.succeededFuture(Answers.internalError(adapterRequest.path(), e))))
                .map(response -> new Answer(context, response, aborted));
    }

    /**
     * The call to DIAL Core through the host, which serves any route of Core's API: a create operation (a POST with a
     * body), or a GET such as the user info of the caller (no body).
     */
    static Handler coreCall(HostDelegate host, Future<Void> aborted) {
        return call -> {
            ObjectNode body;
            try {
                body = call.body().length() == 0 ? null : (ObjectNode) MAPPER.readTree(Buffers.array(call.body()));
            } catch (IOException | ClassCastException e) {
                return Future.failedFuture(e);
            }
            String uri = call.query() == null ? call.path() : call.path() + "?" + call.query();
            return host.call(new Call(call.method(), uri, call.headers(), body)).map(response -> {
                aborted.onComplete(v -> response.cancel());
                return new AdapterResponse(
                        response.statusCode(),
                        response.headers(),
                        new Body.Stream(response.body().pipe()));
            });
        };
    }

    @Override
    public void close() {
        router.close();
    }

    private static final class Answer implements Response {

        private final int status;
        private final MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        private final AnswerBody body;
        private final Promise<Void> aborted;
        private final Pipe<Buffer> pipe; // null for a whole body

        Answer(Context context, AdapterResponse response, Promise<Void> aborted) {
            this.status = response.status();
            this.aborted = aborted;
            Answers.copyForwarded(response.headers(), headers);
            body = new AnswerBody(context);
            switch (response.body()) {
                case Body.Stream stream -> {
                    pipe = stream.pipe().endOnFailure(false);
                    pipe.to(body.sink()).onFailure(e -> {
                        // The upstream broke, so its call is over; DIAL Core cuts the client's response, as the
                        // standalone server resets it.
                        aborted.tryComplete();
                        body.fail(e);
                    });
                }
                case Body.Bytes bytes -> {
                    pipe = null;
                    body.sink().end(bytes.buffer());
                }
            }
        }

        @Override
        public int statusCode() {
            return status;
        }

        @Override
        public MultiMap headers() {
            return headers;
        }

        @Override
        public ReadStream<Buffer> body() {
            return body;
        }

        @Override
        public void cancel() {
            body.cancel(); // first: closing the pipe fails it at once, which would emit the failure inside this call
            aborted.tryComplete();
            if (pipe != null) {
                pipe.close();
            }
        }
    }
}
