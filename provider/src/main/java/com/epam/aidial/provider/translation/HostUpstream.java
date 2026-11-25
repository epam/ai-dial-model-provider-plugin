package com.epam.aidial.provider.translation;

import com.epam.aidial.provider.api.AdapterRequest;
import com.epam.aidial.provider.api.AdapterResponse;
import com.epam.aidial.provider.api.Body;
import com.epam.aidial.provider.api.ModelProvider;
import com.epam.aidial.provider.upstream.ClientGoneException;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.streams.WriteStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Responses call of the translator when it runs as a plugin of DIAL Core: DIAL Core is called in its own process,
 * through the host of the request ({@link AdapterRequest#core()}), with no HTTP call and no key to look up; the call is
 * served with the credentials of the request. What is called is the same as over HTTP (see {@link CoreCallback}).
 */
final class HostUpstream implements ModelProvider {

    private static final Logger log = LoggerFactory.getLogger(HostUpstream.class);

    @Override
    public Future<AdapterResponse> handle(AdapterRequest request) {
        ModelProvider core = request.core();
        if (core == null) {
            throw new IllegalStateException("no host to call DIAL Core through");
        }
        Buffer body = CoreCallback.body(request);
        boolean stream = CoreCallback.streams(body);
        AdapterRequest call = new AdapterRequest(
                HttpMethod.POST,
                request.path(),
                null,
                CoreCallback.headers(request.headers()),
                body,
                request.aborted());
        return core.handle(call)
                .compose(
                        answer -> stream && answer.status() / 100 == 2 ? Future.succeededFuture(answer) : whole(answer))
                .recover(ClientGoneException.unlessGone(request.aborted(), e -> {
                    log.error("Failed to call DIAL Core: {}", e.toString());
                    return Future.succeededFuture(CoreCallback.error(502, "Failed to call DIAL Core"));
                }));
    }

    private static Future<AdapterResponse> whole(AdapterResponse answer) {
        if (answer.body() instanceof Body.Bytes) {
            return Future.succeededFuture(answer);
        }
        Collector collector = new Collector();
        ((Body.Stream) answer.body()).pipe().to(collector).onFailure(collector::fail);
        return collector
                .body()
                .map(body -> new AdapterResponse(answer.status(), answer.headers(), new Body.Bytes(body)));
    }

    private static final class Collector implements WriteStream<Buffer> {

        private final Buffer whole = Buffer.buffer();
        private final Promise<Buffer> done = Promise.promise();

        Future<Buffer> body() {
            return done.future();
        }

        void fail(Throwable cause) {
            done.tryFail(cause);
        }

        @Override
        public Collector exceptionHandler(Handler<Throwable> handler) {
            return this;
        }

        @Override
        public Future<Void> write(Buffer data) {
            whole.appendBuffer(data);
            return Future.succeededFuture();
        }

        @Override
        public void write(Buffer data, Handler<AsyncResult<Void>> handler) {
            handler.handle(write(data));
        }

        @Override
        public void end(Handler<AsyncResult<Void>> handler) {
            done.tryComplete(whole);
            handler.handle(Future.succeededFuture());
        }

        @Override
        public Collector setWriteQueueMaxSize(int maxSize) {
            return this;
        }

        @Override
        public boolean writeQueueFull() {
            return false;
        }

        @Override
        public Collector drainHandler(Handler<Void> handler) {
            return this;
        }
    }
}
