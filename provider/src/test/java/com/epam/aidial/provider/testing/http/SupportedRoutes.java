package com.epam.aidial.provider.testing.http;

import com.epam.aidial.provider.openai.ResponsesRoutes;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Sorts the compiled cases among the classes that run them, by the API their request is to: see {@link Runner}. A new
 * class that runs a kind of cases is a new {@code Runner} constant.
 */
public final class SupportedRoutes {

    /**
     * The classes that run cases, in the order their predicates are tried: the first that accepts a case runs it. The
     * last accepts every case.
     */
    public enum Runner {
        /** {@code MessagesToResponsesCasesTest}: the suite {@code translate}, whatever route it calls. */
        TRANSLATE(c -> SupportedRoutes.suiteOf(c).filter("translate"::equals).isPresent()),
        /** {@code OpenAiResponsesCasesTest}: the cases to a Responses route. */
        RESPONSES(SupportedRoutes::isResponses),
        /**
         * {@code AnthropicCasesTest}: any other case, such as the {@code 404} of a route the service does not serve.
         */
        ANTHROPIC(c -> true);

        private final Predicate<HttpTestCase> accepts;

        Runner(Predicate<HttpTestCase> accepts) {
            this.accepts = accepts;
        }
    }

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

    public static Runner runnerOf(HttpTestCase testCase) {
        return Stream.of(Runner.values())
                .filter(r -> r.accepts.test(testCase))
                .findFirst()
                .orElseThrow();
    }

    /** The cases of every suite on the classpath that {@code runner} runs, by the suite's name. */
    public static Map<String, List<HttpTestCase>> suites(Runner runner) {
        return suites(HttpTestCase.load("http-tests").stream()
                .filter(c -> runnerOf(c) == runner)
                .toList());
    }

    static Map<String, List<HttpTestCase>> suites(List<HttpTestCase> cases) {
        Map<String, List<HttpTestCase>> suites = new TreeMap<>();
        for (HttpTestCase c : cases) {
            suites.computeIfAbsent(suite(c), k -> new ArrayList<>()).add(c);
        }
        return suites;
    }

    static String suite(HttpTestCase c) {
        return suiteOf(c).orElseThrow(() -> new IllegalStateException("not below http-tests: " + c.file()));
    }

    private static Optional<String> suiteOf(HttpTestCase c) {
        Path file = c.file();
        for (int i = file.getNameCount() - 2; i >= 0; i--) {
            if (file.getName(i).toString().equals("http-tests")) {
                return Optional.of(file.getName(i + 1).toString());
            }
        }
        return Optional.empty();
    }
}
