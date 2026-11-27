package com.epam.aidial.provider.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.epam.aidial.provider.api.AdapterResponse;
import com.epam.aidial.provider.api.Body;
import com.epam.aidial.provider.testing.Requests;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import java.util.List;
import org.junit.jupiter.api.Test;

class RoutesTest {

    private final Routes routes = new Routes()
            .add(HttpMethod.POST, "/anthropic/v1/messages", request -> answer("messages"))
            .addById(HttpMethod.GET, "/openai/v1/responses", "", (request, id) -> answer("get " + id))
            .addById(HttpMethod.DELETE, "/openai/v1/responses", "", (request, id) -> answer("delete " + id))
            .addById(HttpMethod.POST, "/openai/v1/responses", "/cancel", (request, id) -> answer("cancel " + id));

    @Test
    void matchesTheDecodedPath() {
        assertEquals("messages", body(handle(HttpMethod.POST, "/anthropic/v1%2Fmessages")));
        assertEquals("get resp 1é", body(handle(HttpMethod.GET, "/openai/v1/responses/resp%201%C3%A9")));
        assertEquals("get a+b", body(handle(HttpMethod.GET, "/openai/v1/responses/a+b")));
        assertEquals("get a+b", body(handle(HttpMethod.GET, "/openai/v1/responses/a%2Bb")));
        assertEquals("get 100%", body(handle(HttpMethod.GET, "/openai/v1/responses/100%")), "a lone % stays");
        assertEquals("get a%zz", body(handle(HttpMethod.GET, "/openai/v1/responses/a%zz")), "a bad escape stays");
    }

    @Test
    void theSuffixFollowsTheId() {
        assertEquals("cancel r1", body(handle(HttpMethod.POST, "/openai/v1/responses/r1/cancel")));
        assertEquals("cancel cancel", body(handle(HttpMethod.POST, "/openai/v1/responses/cancel/cancel")));
        assertEquals(
                404, handle(HttpMethod.POST, "/openai/v1/responses//cancel").status(), "no id");
        assertEquals(
                404, handle(HttpMethod.POST, "/openai/v1/responses/a/b/cancel").status());
        assertEquals(
                404,
                handle(HttpMethod.POST, "/openai/v1/responses/r1/cancelled").status());
        assertEquals(
                "POST",
                handle(HttpMethod.GET, "/openai/v1/responses/r1/cancel")
                        .headers()
                        .get("Allow"));
    }

    @Test
    void literalsAreNotPatterns() {
        Routes table = new Routes()
                .addById(HttpMethod.GET, "/v1.0/a+b", "/x(y)", (request, id) -> answer("hit " + id))
                .add(HttpMethod.GET, "/v1.0/a+b/(c)", request -> answer("literal"));
        assertEquals("hit 7", body(handle(table, HttpMethod.GET, "/v1.0/a+b/7/x(y)")));
        assertEquals("literal", body(handle(table, HttpMethod.GET, "/v1.0/a+b/(c)")));
        assertEquals(404, handle(table, HttpMethod.GET, "/v1x0/a+b/7/x(y)").status());
        assertEquals(404, handle(table, HttpMethod.GET, "/v1.0/aab/7/x(y)").status());
        assertEquals(404, handle(table, HttpMethod.GET, "/v1.0/a+b/7/xy").status());
    }

    @Test
    void decodedSlashEndsTheSegment() {
        AdapterResponse response = handle(HttpMethod.GET, "/openai/v1/responses/a%2Fb");
        assertEquals(404, response.status());
        assertEquals("{\"detail\":\"Not Found\"}", body(response));
    }

    @Test
    void otherMethodIsMethodNotAllowed() {
        AdapterResponse response = handle(HttpMethod.HEAD, "/openai/v1/responses/r");
        assertEquals(405, response.status());
        assertEquals("GET", response.headers().get("Allow"), "the first route of the path");
        assertEquals("{\"detail\":\"Method Not Allowed\"}", body(response));
        assertEquals(
                "POST",
                handle(HttpMethod.GET, "/anthropic/v1/messages").headers().get("Allow"));
    }

    @Test
    void unknownPathIsNotFound() {
        for (String path : List.of("/anthropic/v1/messages/", "/anthropic", "/nope", "/openai/v1/responses/")) {
            assertEquals(404, handle(HttpMethod.POST, path).status(), path);
        }
    }

    private AdapterResponse handle(HttpMethod method, String path) {
        return handle(routes, method, path);
    }

    private static AdapterResponse handle(Routes table, HttpMethod method, String path) {
        Future<AdapterResponse> f = table.handle(Requests.of(
                method,
                path,
                null,
                MultiMap.caseInsensitiveMultiMap(),
                Buffer.buffer(),
                Promise.<Void>promise().future()));
        return f.result();
    }

    private static Future<AdapterResponse> answer(String text) {
        return Future.succeededFuture(AdapterResponse.text(200, text));
    }

    private static String body(AdapterResponse response) {
        return ((Body.Bytes) response.body()).buffer().toString();
    }
}
