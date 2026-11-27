package com.epam.aidial.provider.translation;

import com.epam.aidial.provider.api.AdapterResponse;
import com.epam.aidial.provider.api.Body;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.WriteStream;

final class Bodies {

    private Bodies() {}

    /**
     * The answer with its body read to its end. Fails if the body breaks before it ends: nothing of it is used, and
     * nothing has been written to the client yet.
     */
    static Future<AdapterResponse> whole(AdapterResponse answer) {
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
