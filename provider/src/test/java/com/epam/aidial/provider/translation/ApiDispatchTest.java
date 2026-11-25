package com.epam.aidial.provider.translation;

import static com.epam.aidial.provider.testing.MockUpstream.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.epam.aidial.provider.api.AdapterRequest;
import com.epam.aidial.provider.api.AdapterResponse;
import com.epam.aidial.provider.api.Body;
import com.epam.aidial.provider.api.Handler;
import com.epam.aidial.provider.core.Routes;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class ApiDispatchTest {

    private static final List<String> PATHS = List.of("/anthropic/v1/messages", "/anthropic/v1/messages/count_tokens");
    private static final Routes.Group MESSAGES = routes -> {
        for (String path : PATHS) {
            routes.add(HttpMethod.POST, path, r -> answer("messages " + r.path()));
        }
    };

    private static Future<AdapterResponse> answer(String body) {
        return Future.succeededFuture(AdapterResponse.json(200, body));
    }

    private static String send(Routes routes, HttpMethod method, String path, @Nullable String endpoint)
            throws Exception {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        if (endpoint != null) {
            headers.add("X-Upstream-Endpoint", endpoint);
        }
        AdapterResponse response = await(routes.handle(new AdapterRequest(
                method,
                path,
                null,
                headers,
                Buffer.buffer("{}"),
                Promise.<Void>promise().future())));
        return response.status() + " " + ((Body.Bytes) response.body()).buffer();
    }

    @Test
    void theUpstreamsApiDecidesWhoServesTheRoute() throws Exception {
        Handler responses = r -> answer("responses " + r.path());
        Routes routes = Routes.of(new ApiDispatch(PATHS, MESSAGES, responses));
        for (String path : PATHS) {
            assertEquals(
                    "200 messages " + path, send(routes, HttpMethod.POST, path, "https://h/anthropic/v1/messages"));
            assertEquals("200 responses " + path, send(routes, HttpMethod.POST, path, "https://h/openai/v1/responses"));
            assertEquals("200 responses " + path, send(routes, HttpMethod.POST, path, "https://h/openai/responses"));
        }
    }

    @Test
    void anEndpointThatIsNoResponsesApiIsLeftToThePassthrough() throws Exception {
        Routes routes = Routes.of(new ApiDispatch(PATHS, MESSAGES, r -> answer("responses")));
        for (String endpoint :
                new String[] {null, "", "https://h/messages", "https://h/v1/messages/responses", "https://h/responses/x"
                }) {
            assertEquals(
                    "200 messages /anthropic/v1/messages",
                    send(routes, HttpMethod.POST, "/anthropic/v1/messages", endpoint),
                    String.valueOf(endpoint));
        }
    }

    @Test
    void otherPathsAndMethodsAreTheTablesAsBefore() throws Exception {
        Routes routes = Routes.of(new ApiDispatch(PATHS, MESSAGES, r -> answer("responses")));
        assertEquals("404 {\"detail\":\"Not Found\"}", send(routes, HttpMethod.POST, "/anthropic/v1/other", null));
        assertEquals(
                "405 {\"detail\":\"Method Not Allowed\"}",
                send(routes, HttpMethod.GET, "/anthropic/v1/messages", null));
    }
}
