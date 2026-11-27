package com.epam.aidial.provider.plugin;

import static com.epam.aidial.provider.testing.MockUpstream.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.epam.aidial.core.plugin.Call;
import com.epam.aidial.core.plugin.HostDelegate;
import com.epam.aidial.core.plugin.Response;
import com.epam.aidial.provider.api.AdapterRequest;
import com.epam.aidial.provider.api.AdapterResponse;
import com.epam.aidial.provider.api.Body;
import com.epam.aidial.provider.api.Handler;
import com.epam.aidial.provider.testing.Requests;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.streams.ReadStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** What the plugin asks of DIAL Core through its host: any method, a body or none. */
class CoreCallTest {

    private final List<Call> calls = new ArrayList<>();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final Promise<Void> aborted = Promise.promise();

    private final HostDelegate host = new HostDelegate() {
        @Override
        public Future<Response> call(Call call) {
            calls.add(call);
            return Future.succeededFuture(new Answer());
        }

        @Override
        public Future<Void> cancelled() {
            return Promise.<Void>promise().future();
        }
    };

    private AdapterResponse call(HttpMethod method, String path, String query, String body) throws Exception {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap().set("Api-Key", "k1");
        Handler core = ModelProviderPlugin.coreCall(host, aborted.future());
        AdapterRequest request = Requests.of(
                method,
                path,
                query,
                headers,
                Buffer.buffer(body.getBytes(StandardCharsets.UTF_8)),
                aborted.future(),
                core);
        return await(core.handle(request));
    }

    @Test
    void getHasNoBody() throws Exception {
        AdapterResponse response = call(HttpMethod.GET, "/v1/user/info", null, "");

        assertEquals(1, calls.size());
        Call sent = calls.get(0);
        assertEquals(HttpMethod.GET, sent.method());
        assertEquals("/v1/user/info", sent.uri());
        assertNull(sent.body(), "a GET carries no body");
        assertEquals("k1", sent.headers().get("Api-Key"));
        assertEquals(200, response.status());
        assertEquals("application/json", response.headers().get("Content-Type"));
        assertInstanceOf(Body.Stream.class, response.body());
    }

    @Test
    void postCarriesTheBodyParsed() throws Exception {
        call(HttpMethod.POST, "/openai/v1/responses", "a=1", "{\"model\":\"m\"}");

        Call sent = calls.get(0);
        assertEquals(HttpMethod.POST, sent.method());
        assertEquals("/openai/v1/responses?a=1", sent.uri());
        assertEquals(JsonNodeFactory.instance.objectNode().put("model", "m"), sent.body());
    }

    @Test
    void bodyThatIsNoObjectFailsTheCall() {
        for (String body : List.of("[]", "\"s\"", "{")) {
            Throwable e = assertThrows(ExecutionException.class, () -> call(HttpMethod.POST, "/p", null, body))
                    .getCause();
            assertFalse(e.getMessage().isEmpty(), body);
        }
        assertTrue(calls.isEmpty(), "nothing is sent");
    }

    @Test
    void answerIsCancelledWhenTheRequestIsAborted() throws Exception {
        call(HttpMethod.GET, "/v1/user/info", null, "");
        assertFalse(cancelled.get());

        aborted.complete();

        assertTrue(cancelled.get(), "the call's answer is cancelled with the request");
    }

    private final class Answer implements Response {

        @Override
        public int statusCode() {
            return 200;
        }

        @Override
        public MultiMap headers() {
            return MultiMap.caseInsensitiveMultiMap().set("Content-Type", "application/json");
        }

        @Override
        public ReadStream<Buffer> body() {
            return new Empty();
        }

        @Override
        public void cancel() {
            cancelled.set(true);
        }
    }

    /** A stream with no data that never ends: the tests do not read it. */
    private static final class Empty implements ReadStream<Buffer> {

        @Override
        public Empty exceptionHandler(io.vertx.core.Handler<Throwable> handler) {
            return this;
        }

        @Override
        public Empty handler(io.vertx.core.Handler<Buffer> handler) {
            return this;
        }

        @Override
        public Empty pause() {
            return this;
        }

        @Override
        public Empty resume() {
            return this;
        }

        @Override
        public Empty fetch(long amount) {
            return this;
        }

        @Override
        public Empty endHandler(io.vertx.core.Handler<Void> handler) {
            return this;
        }
    }
}
