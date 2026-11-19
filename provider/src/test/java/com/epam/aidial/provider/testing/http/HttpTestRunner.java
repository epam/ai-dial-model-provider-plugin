package com.epam.aidial.provider.testing.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HttpTestRunner implements AutoCloseable {

    @FunctionalInterface
    public interface Target {
        /**
         * {@code server} is the case's {@code server} member (an empty object if it has none) with the mock servers'
         * URLs substituted.
         */
        URI prepare(JsonNode server) throws Exception;
    }

    @FunctionalInterface
    public interface Aggregate {
        void check(List<Sse.Event> events, JsonNode spec, JsonMatch m, String path);
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$?\\$\\{mocks\\.([A-Za-z0-9_-]+)\\.url}");
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    /** A mock not called yet when the answer has ended is waited for this long: calls may still be in flight. */
    private static final Duration LATE_CALLS = Duration.ofSeconds(2);

    private static final JsonNode ABSENT = JsonMatch.JSON.createObjectNode().put("$absent", true);

    private final Target target;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "http-test-runner");
        t.setDaemon(true);
        return t;
    });
    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(TIMEOUT)
            .build();
    private final Map<String, MockServer> servers = new LinkedHashMap<>();
    private final Map<String, JsonMatch.Operator> operators;
    private final Map<String, Aggregate> aggregates = new HashMap<>(Map.of(
            "concat", HttpTestRunner::concat,
            "tool_calls", HttpTestRunner::toolCalls,
            "last_value", HttpTestRunner::lastValue,
            "last_event",
                    (events, spec, m, path) -> {
                        if (events.isEmpty()) {
                            m.fail(path, "no events");
                        } else {
                            event(spec.get("equals"), events.getLast(), m, path);
                        }
                    },
            "count",
                    (events, spec, m, path) ->
                            m.match(spec.get("equals"), IntNode.valueOf(events.size()), path, false)));

    public HttpTestRunner(Target target) {
        this.target = target;
        this.operators = new HashMap<>(JsonMatch.operators());
    }

    public HttpTestRunner operator(String name, JsonMatch.Operator operator) {
        operators.put(name, operator);
        return this;
    }

    public HttpTestRunner aggregate(String type, Aggregate aggregate) {
        aggregates.put(type, aggregate);
        return this;
    }

    /** Throws one AssertionError listing everything that did not match. */
    public void run(HttpTestCase testCase) throws Exception {
        JsonNode raw = testCase.content();
        Set<String> names = new LinkedHashSet<>();
        raw.path("mocks").forEach(mock -> names.add(mock.get("server").asText()));
        collectPlaceholders(raw, names);
        Map<String, String> urls = new HashMap<>();
        for (String name : names) {
            urls.put(name, server(name).url);
        }
        JsonNode c = substitute(raw, urls);
        boolean strictMembers = c.at("/options/json_member_order").asText("any").equals("strict");
        boolean anyOrder = c.at("/options/mock_order").asText("strict").equals("any");
        for (MockServer server : servers.values()) {
            List<JsonNode> mocks = new ArrayList<>();
            c.path("mocks").forEach(mock -> {
                if (mock.get("server").asText().equals(server.name)) {
                    mocks.add(mock);
                }
            });
            server.reset(mocks, anyOrder, strictMembers);
        }
        URI base = target.prepare(c.has("server") ? c.get("server") : JsonMatch.JSON.createObjectNode());
        JsonMatch m = new JsonMatch(strictMembers, Map.copyOf(operators));
        try {
            expect(c.get("expect"), send(base, c.get("request")), m);
        } catch (IOException e) {
            m.fail("response", "no response: " + e);
        }
        List<String> failures = new ArrayList<>(m.failures());
        long deadline = System.nanoTime() + LATE_CALLS.toNanos();
        for (MockServer server : servers.values()) {
            server.awaitCalls(deadline);
            failures.addAll(server.verify());
        }
        if (!failures.isEmpty()) {
            throw new AssertionError("case " + testCase + ": " + failures.size() + " failure(s)\n  - "
                    + String.join("\n  - ", failures));
        }
    }

    @Override
    public void close() {
        servers.values().forEach(s -> s.http.stop(0));
        client.shutdownNow();
        executor.shutdownNow();
    }

    private record Response(
            int status,
            Map<String, List<String>> headers,
            byte[] body,
            List<Sse.Event> events,
            boolean incomplete,
            boolean truncated) {}

    private Response send(URI base, JsonNode request) throws Exception {
        String uri = base.toString().replaceAll("/$", "") + request.get("path").asText() + query(request.get("query"));
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(uri))
                .timeout(TIMEOUT)
                .method(request.get("method").asText(), publisher(request));
        request.path("headers").properties().forEach(h -> {
            try {
                builder.header(h.getKey(), h.getValue().asText());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("request header " + h.getKey() + ": " + e.getMessage(), e);
            }
        });
        HttpResponse<InputStream> answer = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        Sse.Parser parser = new Sse.Parser();
        boolean[] truncated = {false};
        Future<?> reading = executor.submit(() -> {
            try (InputStream in = answer.body()) {
                byte[] buffer = new byte[8192];
                for (int n; (n = in.read(buffer)) >= 0; ) {
                    body.write(buffer, 0, n);
                    parser.feed(buffer, 0, n); // as the transport delivers it, not as events
                }
            } catch (IOException e) {
                truncated[0] = true;
            }
        });
        try {
            reading.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            reading.cancel(true);
            throw new AssertionError("the response body did not end within " + TIMEOUT.toSeconds() + " s", e);
        }
        Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.putAll(answer.headers().map());
        List<Sse.Event> events = parser.finish();
        return new Response(
                answer.statusCode(), headers, body.toByteArray(), events, parser.incomplete(), truncated[0]);
    }

    private static HttpRequest.BodyPublisher publisher(JsonNode message) throws JsonProcessingException {
        byte[] bytes = bytes(message);
        return bytes == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(bytes);
    }

    /** Body bytes from body (UTF-8 text), body_base64, json (serialized) or sse; null if none. */
    public static byte[] bytes(JsonNode message) throws JsonProcessingException {
        if (message.has("body")) {
            return message.get("body").asText().getBytes(StandardCharsets.UTF_8);
        }
        if (message.has("body_base64")) {
            return Base64.getDecoder().decode(message.get("body_base64").asText());
        }
        if (message.has("json")) {
            return JsonMatch.JSON.writeValueAsBytes(message.get("json"));
        }
        if (message.has("sse")) {
            String end = message.get("sse").path("line_ending").asText("\n");
            StringBuilder out = new StringBuilder();
            for (JsonNode e : message.get("sse").get("events")) {
                if (e.has("comment")) {
                    out.append(':').append(e.get("comment").asText()).append(end);
                }
                for (String field : List.of("event", "id", "retry")) {
                    if (e.has(field)) {
                        out.append(field)
                                .append(": ")
                                .append(e.get(field).asText())
                                .append(end);
                    }
                }
                String data = e.has("json")
                        ? JsonMatch.JSON.writeValueAsString(e.get("json"))
                        : e.path("data").asText(null);
                if (data != null) {
                    for (String line : data.split("\n", -1)) {
                        out.append("data: ").append(line).append(end);
                    }
                }
                out.append(end);
            }
            return out.toString().getBytes(StandardCharsets.UTF_8);
        }
        return null;
    }

    private static String query(JsonNode query) {
        if (query == null || query.isEmpty()) {
            return "";
        }
        List<String> pairs = new ArrayList<>();
        query.properties().forEach(q -> {
            for (JsonNode v : q.getValue().isArray() ? q.getValue() : List.of(q.getValue())) {
                pairs.add(URLEncoder.encode(q.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(v.asText(), StandardCharsets.UTF_8));
            }
        });
        return "?" + String.join("&", pairs);
    }

    private void expect(JsonNode expect, Response r, JsonMatch m) {
        String body = new String(r.body(), StandardCharsets.UTF_8);
        if (expect.has("status") && expect.get("status").asInt() != r.status()) {
            m.fail(
                    "response.status",
                    "expected " + expect.get("status").asInt() + " but was " + r.status() + ", body "
                            + JsonMatch.show(TextNode.valueOf(body)));
        }
        headers(expect.get("headers"), r.headers(), "response.headers", m);
        if (!expect.has("sse") && r.truncated()) {
            m.fail("response.body", "the connection was cut before the body ended");
        }
        if (expect.has("body")) {
            m.match(expect.get("body"), TextNode.valueOf(body), "response.body", false);
        }
        if (expect.has("body_base64")) {
            m.match(
                    expect.get("body_base64"),
                    TextNode.valueOf(Base64.getEncoder().encodeToString(r.body())),
                    "response.body_base64",
                    false);
        }
        if (expect.has("json")) {
            json(expect.get("json"), body, m, "response.json");
        }
        if (expect.has("sse")) {
            sse(expect.get("sse"), r, m);
        }
    }

    private static void json(JsonNode expected, String text, JsonMatch m, String path) {
        if (text.isBlank()) {
            m.fail(path, "expected JSON " + JsonMatch.show(expected) + " but the body is empty");
            return;
        }
        try {
            m.match(expected, JsonMatch.JSON.readTree(text), path, false);
        } catch (JsonProcessingException e) {
            m.fail(
                    path,
                    "the body is not JSON (" + e.getOriginalMessage() + "): " + JsonMatch.show(TextNode.valueOf(text)));
        }
    }

    private void sse(JsonNode expect, Response r, JsonMatch m) {
        String termination = expect.path("termination").asText("complete");
        String actual = r.truncated() ? "truncated" : r.incomplete() ? "incomplete_event" : "complete";
        if (!termination.equals(actual)) {
            m.fail("response.sse.termination", "expected " + termination + " but the stream was " + actual);
        }
        List<Sse.Event> events = r.events();
        if (expect.has("events")) {
            JsonNode expected = expect.get("events");
            if (expect.path("ordered").asBoolean(true)) {
                if (expected.size() != events.size()) {
                    m.fail(
                            "response.sse.events",
                            "expected " + expected.size() + " events but got " + events.size() + ": " + shown(events));
                }
                for (int i = 0; i < Math.min(expected.size(), events.size()); i++) {
                    event(expected.get(i), events.get(i), m, "response.sse.events[" + i + "]");
                }
            } else {
                unorderedEvents(expected, events, m);
            }
        }
        JsonNode checks = expect.path("aggregate");
        for (int i = 0; i < checks.size(); i++) {
            String type = checks.get(i).path("type").asText();
            Aggregate aggregate = aggregates.get(type);
            String path = "response.sse.aggregate[" + i + "] (" + type + ")";
            if (aggregate == null) {
                m.fail(path, "unknown aggregate type (known: " + aggregates.keySet() + ")");
            } else {
                aggregate.check(events, checks.get(i), m, path);
            }
        }
    }

    private static void unorderedEvents(JsonNode expected, List<Sse.Event> events, JsonMatch m) {
        if (expected.size() != events.size()) {
            m.fail(
                    "response.sse.events",
                    "expected " + expected.size() + " events in any order but got " + events.size() + ": "
                            + shown(events));
        }
        boolean[] used = new boolean[events.size()];
        for (int i = 0; i < expected.size(); i++) {
            boolean found = false;
            for (int j = 0; j < events.size() && !found; j++) {
                if (!used[j]) {
                    JsonMatch trial = m.child();
                    event(expected.get(i), events.get(j), trial, "");
                    used[j] = found = trial.failures().isEmpty();
                }
            }
            if (!found) {
                m.fail(
                        "response.sse.events[" + i + "]",
                        "no event matches " + JsonMatch.show(expected.get(i)) + " (unordered)");
            }
        }
    }

    private static void event(JsonNode expected, Sse.Event e, JsonMatch m, String path) {
        if (expected.has("event")) {
            m.match(expected.get("event"), TextNode.valueOf(e.event()), path + ".event", false);
        }
        if (expected.has("id")) {
            m.match(expected.get("id"), e.id() == null ? null : TextNode.valueOf(e.id()), path + ".id", false);
        }
        if (expected.has("retry")) {
            m.match(
                    expected.get("retry"),
                    e.retry() == null ? null : IntNode.valueOf(e.retry()),
                    path + ".retry",
                    false);
        }
        if (expected.has("data")) {
            m.match(expected.get("data"), TextNode.valueOf(e.data()), path + ".data", false);
        }
        if (expected.has("json")) {
            json(expected.get("json"), e.data(), m, path + ".json");
        }
    }

    private static String shown(List<Sse.Event> events) {
        List<String> data = new ArrayList<>();
        events.forEach(e -> data.add(e.data().length() > 80 ? e.data().substring(0, 80) + "..." : e.data()));
        return data.toString();
    }

    private static List<JsonNode> payloads(List<Sse.Event> events) {
        List<JsonNode> out = new ArrayList<>();
        for (Sse.Event e : events) {
            try {
                out.add(JsonMatch.JSON.readTree(e.data()));
            } catch (JsonProcessingException ignored) {
                // not JSON: not a payload
            }
        }
        return out;
    }

    /** Numbers in the dotted path index arrays; null when absent. */
    static JsonNode at(JsonNode node, String path) {
        for (String segment : path.isEmpty() ? new String[0] : path.split("\\.")) {
            if (node == null) {
                return null;
            }
            node = node.isArray() && segment.chars().allMatch(Character::isDigit)
                    ? node.get(Integer.parseInt(segment))
                    : node.get(segment);
        }
        return node;
    }

    private static void concat(List<Sse.Event> events, JsonNode spec, JsonMatch m, String path) {
        StringBuilder out = new StringBuilder();
        for (JsonNode p : payloads(events)) {
            JsonNode v = at(p, spec.path("path").asText());
            if (v != null && v.isTextual()) {
                out.append(v.asText());
            }
        }
        m.match(spec.get("equals"), TextNode.valueOf(out.toString()), path, false);
    }

    private static void lastValue(List<Sse.Event> events, JsonNode spec, JsonMatch m, String path) {
        JsonNode last = null;
        for (JsonNode p : payloads(events)) {
            JsonNode v = at(p, spec.path("path").asText());
            if (v != null && !v.isNull()) {
                last = v;
            }
        }
        m.match(spec.get("equals"), last, path, false);
    }

    /**
     * OpenAI-style streamed tool calls rebuilt from the arrays at {@code path}: merged by {@code index}, the first id,
     * type and function name kept, the argument fragments concatenated; compared as an array ordered by index.
     */
    private static void toolCalls(List<Sse.Event> events, JsonNode spec, JsonMatch m, String path) {
        Map<Integer, ObjectNode> calls = new TreeMap<>();
        for (JsonNode p : payloads(events)) {
            JsonNode deltas = at(p, spec.path("path").asText());
            if (deltas == null || !deltas.isArray()) {
                continue;
            }
            for (JsonNode delta : deltas) {
                ObjectNode call = calls.computeIfAbsent(delta.path("index").asInt(0), i -> {
                    ObjectNode o = JsonMatch.JSON.createObjectNode();
                    o.putObject("function").put("arguments", "");
                    return o;
                });
                for (String field : List.of("id", "type")) {
                    if (!call.has(field) && delta.hasNonNull(field)) {
                        call.set(field, delta.get(field));
                    }
                }
                JsonNode function = delta.path("function");
                ObjectNode merged = (ObjectNode) call.get("function");
                if (!merged.has("name") && function.hasNonNull("name")) {
                    merged.set("name", function.get("name"));
                }
                if (function.hasNonNull("arguments")) {
                    merged.put(
                            "arguments",
                            merged.get("arguments").asText()
                                    + function.get("arguments").asText());
                }
            }
        }
        ArrayNode rebuilt = JsonMatch.JSON.createArrayNode();
        calls.values().forEach(rebuilt::add);
        m.match(spec.get("equals"), rebuilt, path, false);
    }

    /**
     * Name matching is case-insensitive and the values are joined by ", ". A name with {@code *} is a pattern taking
     * only {@code {$absent: true}}: no header it matches is there but those named.
     */
    private static void headers(JsonNode expected, Map<String, List<String>> actual, String path, JsonMatch m) {
        if (expected == null) {
            return;
        }
        Map<String, JsonNode> named = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        List<Pattern> absent = new ArrayList<>();
        expected.properties().forEach(h -> {
            if (!h.getKey().contains("*")) {
                named.put(h.getKey(), h.getValue());
            } else if (ABSENT.equals(h.getValue())) {
                absent.add(
                        Pattern.compile(Pattern.quote(h.getKey()).replace("*", "\\E.*\\Q"), Pattern.CASE_INSENSITIVE));
            } else {
                m.fail(path + "." + h.getKey(), "a header pattern takes only {$absent: true}");
            }
        });
        named.forEach((name, value) -> {
            List<String> values = actual.get(name);
            m.match(
                    value,
                    values == null ? null : TextNode.valueOf(String.join(", ", values)),
                    path + "." + name,
                    false);
        });
        actual.forEach((name, values) -> {
            if (!named.containsKey(name)
                    && absent.stream().anyMatch(p -> p.matcher(name).matches())) {
                m.fail(path + "." + name.toLowerCase(Locale.ROOT), "unexpected header: " + String.join(", ", values));
            }
        });
    }

    private static void collectPlaceholders(JsonNode node, Set<String> names) {
        if (node.isTextual()) {
            Matcher matcher = PLACEHOLDER.matcher(node.asText());
            while (matcher.find()) {
                if (!matcher.group().startsWith("$$")) {
                    names.add(matcher.group(1));
                }
            }
        }
        node.forEach(child -> collectPlaceholders(child, names));
    }

    /** ${mocks.NAME.url} becomes that server's URL; $${ writes a literal ${. */
    private static JsonNode substitute(JsonNode node, Map<String, String> urls) {
        if (node.isTextual()) {
            return TextNode.valueOf(PLACEHOLDER
                    .matcher(node.asText())
                    .replaceAll(r -> Matcher.quoteReplacement(
                            r.group().startsWith("$$") ? r.group().substring(1) : urls.get(r.group(1)))));
        }
        if (node.isObject()) {
            ObjectNode out = JsonMatch.JSON.createObjectNode();
            node.properties().forEach(e -> out.set(e.getKey(), substitute(e.getValue(), urls)));
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = JsonMatch.JSON.createArrayNode();
            node.forEach(e -> out.add(substitute(e, urls)));
            return out;
        }
        return node;
    }

    private MockServer server(String name) throws IOException {
        MockServer server = servers.get(name);
        if (server == null) {
            server = new MockServer(name);
            servers.put(name, server);
        }
        return server;
    }

    private record Call(String method, String path, String query, Map<String, List<String>> headers, byte[] body) {}

    private final class MockServer {
        final String name;
        final HttpServer http;
        final String url;
        private List<JsonNode> mocks = List.of();
        private boolean[] used = new boolean[0];
        private int calls;
        private boolean anyOrder;
        private boolean strictMembers;
        private final List<String> failures = new ArrayList<>();

        MockServer(String name) throws IOException {
            this.name = name;
            http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            http.createContext("/", this::handle);
            http.setExecutor(executor);
            http.start();
            url = "http://127.0.0.1:" + http.getAddress().getPort();
        }

        synchronized void reset(List<JsonNode> mocks, boolean anyOrder, boolean strictMembers) {
            this.mocks = mocks;
            this.used = new boolean[mocks.size()];
            this.calls = 0;
            this.anyOrder = anyOrder;
            this.strictMembers = strictMembers;
            failures.clear();
        }

        synchronized void awaitCalls(long deadline) throws InterruptedException {
            for (long left = deadline - System.nanoTime();
                    left > 0 && !allUsed();
                    left = deadline - System.nanoTime()) {
                TimeUnit.NANOSECONDS.timedWait(this, left);
            }
        }

        private boolean allUsed() {
            for (boolean u : used) {
                if (!u) {
                    return false;
                }
            }
            return true;
        }

        synchronized List<String> verify() {
            List<String> out = new ArrayList<>(failures);
            for (int i = 0; i < mocks.size(); i++) {
                if (!used[i]) {
                    JsonNode request = mocks.get(i).get("request");
                    out.add(label(i) + ": never called ("
                            + request.get("method").asText() + " "
                            + request.get("path").asText() + ")");
                }
            }
            return out;
        }

        private String label(int index) {
            JsonNode mock = mocks.get(index);
            return "mocks " + name + "#" + (index + 1)
                    + (mock.has("name") ? " '" + mock.get("name").asText() + "'" : "");
        }

        private void handle(HttpExchange exchange) throws IOException {
            try (exchange) {
                Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                headers.putAll(exchange.getRequestHeaders());
                Call call = new Call(
                        exchange.getRequestMethod(),
                        exchange.getRequestURI().getRawPath(),
                        exchange.getRequestURI().getRawQuery(),
                        headers,
                        exchange.getRequestBody().readAllBytes());
                JsonNode response = answer(call);
                byte[] body = response == null
                        ? ("no mock for this call (" + name + "): see the case's failures")
                                .getBytes(StandardCharsets.UTF_8)
                        : bytes(response);
                if (response != null) {
                    response.path("headers").properties().forEach(h -> exchange.getResponseHeaders()
                            .add(h.getKey(), h.getValue().asText()));
                }
                body = body == null ? new byte[0] : body;
                exchange.sendResponseHeaders(
                        response == null ? 500 : response.get("status").asInt(), body.length == 0 ? -1 : body.length);
                if (body.length > 0) {
                    exchange.getResponseBody().write(body);
                }
            }
        }

        /** Null when no mock takes the call, which is recorded as a mismatch. */
        private synchronized JsonNode answer(Call call) {
            notifyAll(); // awaitCalls
            int n = ++calls;
            String what = "mocks " + name + " call #" + n + " (" + call.method() + " " + call.path() + ")";
            if (!anyOrder) {
                if (n > mocks.size()) {
                    failures.add(what + ": unexpected, only " + mocks.size() + " declared for " + name);
                    return null;
                }
                used[n - 1] = true;
                failures.addAll(differences(mocks.get(n - 1).get("request"), call, label(n - 1)));
                return mocks.get(n - 1).get("response");
            }
            List<String> closest = null;
            for (int i = 0; i < mocks.size(); i++) {
                if (!used[i]) {
                    List<String> differences = differences(mocks.get(i).get("request"), call, label(i));
                    if (differences.isEmpty()) {
                        used[i] = true;
                        return mocks.get(i).get("response");
                    }
                    closest = closest == null ? differences : closest;
                }
            }
            failures.add(what + ": matches no unused mock" + (closest == null ? "" : "; the first unused: " + closest));
            return null;
        }

        private List<String> differences(JsonNode expected, Call call, String label) {
            JsonMatch m = new JsonMatch(strictMembers, Map.copyOf(operators));
            String path = label + ".request";
            m.match(expected.get("method"), TextNode.valueOf(call.method()), path + ".method", true);
            m.match(expected.get("path"), TextNode.valueOf(call.path()), path + ".path", true);
            m.match(
                    expected.has("query") ? expected.get("query") : JsonMatch.JSON.createObjectNode(),
                    parseQuery(call.query()),
                    path + ".query",
                    true);
            headers(expected.get("headers"), call.headers(), path + ".headers", m);
            String text = new String(call.body(), StandardCharsets.UTF_8);
            if (expected.has("body")) {
                m.match(expected.get("body"), TextNode.valueOf(text), path + ".body", false);
            }
            if (expected.has("body_base64")) {
                m.match(
                        expected.get("body_base64"),
                        TextNode.valueOf(Base64.getEncoder().encodeToString(call.body())),
                        path + ".body_base64",
                        true);
            }
            if (expected.has("json")) {
                json(expected.get("json"), text, m, path + ".json");
            }
            if (expected.has("multipart")) {
                List<String> type = call.headers().get("Content-Type");
                m.match(
                        expected.get("multipart"),
                        parts(expected.get("multipart"), type == null ? "" : type.getFirst(), call.body()),
                        path + ".multipart",
                        false);
            }
            return m.failures();
        }
    }

    private static JsonNode parseQuery(String raw) {
        ObjectNode out = JsonMatch.JSON.createObjectNode();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String name = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            JsonNode existing = out.get(name);
            if (existing == null) {
                out.put(name, value);
            } else if (existing.isArray()) {
                ((ArrayNode) existing).add(value);
            } else {
                out.putArray(name).add(existing).add(value);
            }
        }
        return out;
    }

    /**
     * A multipart body's parts, each with the members its expectation names; filename and content_type are "" when the
     * part has none.
     */
    private static JsonNode parts(JsonNode expected, String contentType, byte[] body) {
        ArrayNode out = JsonMatch.JSON.createArrayNode();
        int at = contentType.indexOf("boundary=");
        if (at < 0) {
            return TextNode.valueOf("not multipart: Content-Type " + contentType);
        }
        String boundary = contentType.substring(at + "boundary=".length()).replace("\"", "");
        String text = new String(body, StandardCharsets.ISO_8859_1);
        for (String part : text.split("--" + Pattern.quote(boundary))) {
            int split = part.indexOf("\r\n\r\n");
            if (split < 0) {
                continue;
            }
            String head = part.substring(0, split);
            byte[] content = part.substring(split + 4, Math.max(split + 4, part.length() - 2))
                    .getBytes(StandardCharsets.ISO_8859_1);
            JsonNode want = expected.path(out.size());
            ObjectNode p = out.addObject();
            p.put("name", group(head, "name=\"([^\"]*)\""));
            if (want.has("filename")) {
                p.put("filename", group(head, "filename=\"([^\"]*)\""));
            }
            if (want.has("content_type")) {
                p.put(
                        "content_type",
                        group(head, "(?i)content-type: *([^\r\n]*)").strip());
            }
            if (want.has("body")) {
                p.put("body", new String(content, StandardCharsets.UTF_8));
            }
            if (want.has("body_base64")) {
                p.put("body_base64", Base64.getEncoder().encodeToString(content));
            }
            if (want.has("json")) {
                try {
                    p.set("json", JsonMatch.JSON.readTree(content));
                } catch (IOException e) {
                    p.put("json", "not JSON: " + new String(content, StandardCharsets.UTF_8));
                }
            }
        }
        return out;
    }

    private static String group(String text, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        return matcher.find() ? matcher.group(1) : "";
    }
}
