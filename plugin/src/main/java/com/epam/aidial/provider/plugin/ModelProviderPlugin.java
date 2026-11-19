package com.epam.aidial.provider.plugin;

import com.epam.aidial.core.plugin.HostDelegate;
import com.epam.aidial.core.plugin.Plugin;
import com.epam.aidial.core.plugin.Request;
import com.epam.aidial.core.plugin.Response;
import com.epam.aidial.provider.api.AdapterRequest;
import com.epam.aidial.provider.api.AdapterResponse;
import com.epam.aidial.provider.api.Body;
import com.epam.aidial.provider.core.Answers;
import com.epam.aidial.provider.core.CompatRouter;
import com.epam.aidial.provider.core.HttpServerShim;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.Pipe;
import io.vertx.core.streams.ReadStream;

/**
 * DIAL Core's request becomes the request the standalone server makes of the same request below
 * {@code /compat/<adapter>}, and the answer what that server would write ({@link HttpServerShim}).
 */
final class ModelProviderPlugin implements Plugin {

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
                aborted.future());
        return Answers.call(router, adapterRequest)
                .recover(e -> {
                    if (aborted.future().isComplete()) {
                        // DIAL Core stopped waiting (ClientGoneException): a failed answer ends its request quietly, as
                        // the standalone server writes nothing to a client that has gone
                        return Future.failedFuture(e);
                    }
                    return Future.succeededFuture(Answers.internalError(adapterRequest.path(), e));
                })
                .map(response -> new Answer(context, response, aborted));
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
