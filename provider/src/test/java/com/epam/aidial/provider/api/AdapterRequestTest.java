package com.epam.aidial.provider.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.epam.aidial.provider.testing.Requests;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AdapterRequestTest {

    private static AdapterRequest request(MultiMap headers) {
        return Requests.of(
                HttpMethod.POST, "/v1/messages", null, headers, Buffer.buffer(), Future.<Void>future(p -> {}));
    }

    private static AdapterRequest withQuery(String query) {
        return Requests.of(
                HttpMethod.GET,
                "/v1/responses",
                query,
                MultiMap.caseInsensitiveMultiMap(),
                Buffer.buffer(),
                Future.<Void>future(p -> {}));
    }

    @Test
    void queryParamsKeepTheFirstPositionAndTheLastValueOfRepeatedNames() {
        assertEquals(
                List.of("b=2", "a=x y", "e=", "api-version=v", "f="),
                withQuery("b=1&&a=x%20y&b=2&e=&api-version=v&f").queryParams().entrySet().stream()
                        .map(e -> e.getKey() + "=" + e.getValue())
                        .toList());
    }

    @Test
    void queryParamsDecodePlusAndKeepMalformedEscapesAsTheyAre() {
        assertEquals(Map.of("q", "a b"), withQuery("q=a+b").queryParams());
        assertEquals(Map.of("t", "=&"), withQuery("t=%3D%26").queryParams());
        assertEquals(Map.of("x", "%zz"), withQuery("x=%zz").queryParams());
        assertEquals(Map.of("a", "1=2"), withQuery("a=1=2").queryParams());
    }

    @Test
    void apiVersionIsTheLastOneAndNotEmpty() {
        assertEquals("b", withQuery("api-version=a&x=1&api-version=b").requiredApiVersion());
        for (String query : new String[] {"x=1", "api-version=", "api-version=a&api-version="}) {
            InvalidRequestException e = assertThrows(
                    InvalidRequestException.class, () -> withQuery(query).requiredApiVersion(), query);
            assertEquals("api-version is a required query parameter", e.getMessage());
        }
        assertThrows(InvalidRequestException.class, () -> request(MultiMap.caseInsensitiveMultiMap())
                .requiredApiVersion());
    }

    @Test
    void noQueryHasNoParams() {
        assertEquals(Map.of(), request(MultiMap.caseInsensitiveMultiMap()).queryParams());
        assertEquals(Map.of(), withQuery("").queryParams());
    }
}
