package com.epam.aidial.provider.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.epam.aidial.provider.api.AdapterResponse;
import com.epam.aidial.provider.api.Body;
import org.junit.jupiter.api.Test;

class AnthropicErrorsTest {

    @Test
    void errorIsTheBodyEveryAnthropicClientReads() {
        AdapterResponse response = AnthropicErrors.error(529, "overloaded_error", "Overloaded \"now\"");
        assertEquals(529, response.status());
        assertEquals("application/json", response.headers().get("Content-Type"));
        assertEquals(
                """
                {"error":{"message":"Overloaded \\"now\\"","type":"overloaded_error"},"type":"error"}
                """,
                ((Body.Bytes) response.body()).buffer().toString());
    }
}
