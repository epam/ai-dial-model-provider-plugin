package com.epam.aidial.provider.testing.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.epam.aidial.provider.openai.ResponsesRoutes;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SupportedRoutesTest {

    private static HttpTestCase testCase(String path) throws Exception {
        String yaml = "name: n\n"
                + "request: {method: POST, path: " + path + ", json: {a: 1}}\n"
                + "expect: {status: 200, json: {ok: true}}\n";
        Path file = Files.createTempFile("case", ".yaml");
        try {
            Files.writeString(file, yaml);
            return HttpTestCase.read(file).getFirst();
        } finally {
            Files.delete(file);
        }
    }

    @Test
    void theResponsesRoutesAreTheResponsesApi() throws Exception {
        for (String base : ResponsesRoutes.BASES) {
            for (String suffix : new String[] {"", "/input_tokens", "/resp_1", "/resp_1/cancel"}) {
                assertTrue(SupportedRoutes.isResponses(testCase(base + suffix)), base + suffix);
            }
        }
    }

    @Test
    void theFirstRunnerThatAcceptsRunsTheCase() throws Exception {
        assertEquals(SupportedRoutes.Runner.RESPONSES, SupportedRoutes.runnerOf(testCase("/openai/v1/responses")));
        assertEquals(SupportedRoutes.Runner.ANTHROPIC, SupportedRoutes.runnerOf(testCase("/anthropic/v1/messages")));
    }

    @Test
    void anyOtherRouteIsNotResponses() throws Exception {
        for (String path : new String[] {
            "/openai/deployments/gpt-4/chat/completions",
            "/openai/v1/responses/a/b/c",
            "/openai/v1/responses/a/cancel/x",
            "/anthropic/v1/messages",
            "/"
        }) {
            assertFalse(SupportedRoutes.isResponses(testCase(path)), path);
        }
    }
}
