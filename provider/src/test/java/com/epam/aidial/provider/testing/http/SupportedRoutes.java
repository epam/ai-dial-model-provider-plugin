package com.epam.aidial.provider.testing.http;

import com.epam.aidial.provider.openai.ResponsesRoutes;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Sorts the compiled cases by the API their request is to: a case to a Responses route is {@code
 * OpenAiResponsesCasesTest}'s, any other, such as the {@code 404} of a route the service does not serve, is the
 * Anthropic class's.
 */
public final class SupportedRoutes {

    private static final Pattern RESPONSES = Pattern.compile("^(?:"
            + String.join(
                    "|", ResponsesRoutes.BASES.stream().map(Pattern::quote).toList())
            + ")(?:/input_tokens|/[^/]+(?:/cancel)?)?$");

    private SupportedRoutes() {}

    public static boolean isResponses(HttpTestCase testCase) {
        return RESPONSES
                .matcher(testCase.content().at("/request/path").asText())
                .matches();
    }

    public static Map<String, List<HttpTestCase>> suites(List<HttpTestCase> cases) {
        Map<String, List<HttpTestCase>> suites = new TreeMap<>();
        for (HttpTestCase c : cases) {
            suites.computeIfAbsent(suite(c), k -> new ArrayList<>()).add(c);
        }
        return suites;
    }

    private static String suite(HttpTestCase c) {
        Path file = c.file();
        for (int i = file.getNameCount() - 2; i >= 0; i--) {
            if (file.getName(i).toString().equals("http-tests")) {
                return file.getName(i + 1).toString();
            }
        }
        throw new IllegalStateException("not below http-tests: " + file);
    }
}
