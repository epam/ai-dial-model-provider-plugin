package com.epam.aidial.provider.testing.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HttpTestRunnerTest {

    @TempDir
    Path dir;

    private HttpServer service;
    private volatile String upstream;
    private HttpTestRunner runner;

    @Test
    void sseEventsDoNotDependOnHowTheBodyIsCut() {
        String stream = "﻿: heartbeat\r\ndata: {\"a\":\r\ndata: 1}\r\n\r\nevent: delta\nid: 7\nretry: 50\ndata\n\n"
                + "data: x\r\rdata: é\r\n\r\n: only a comment\n\ndata: tail";
        byte[] bytes = stream.getBytes(StandardCharsets.UTF_8);
        List<Sse.Event> whole = parse(bytes, bytes.length);
        assertEquals(
                List.of(
                        new Sse.Event("message", "{\"a\":\n1}", null, null),
                        new Sse.Event("delta", "", "7", 50),
                        new Sse.Event("message", "x", "7", null),
                        new Sse.Event("message", "é", "7", null)),
                whole);
        for (int size = 1; size < 8; size++) {
            assertEquals(whole, parse(bytes, size), "pieces of " + size + " bytes");
        }
        Sse.Parser parser = new Sse.Parser();
        parser.feed(bytes, 0, bytes.length);
        parser.finish();
        assertTrue(parser.incomplete(), "data: tail has no blank line after it");
    }

    private static List<Sse.Event> parse(byte[] bytes, int piece) {
        Sse.Parser parser = new Sse.Parser();
        for (int i = 0; i < bytes.length; i += piece) {
            parser.feed(bytes, i, Math.min(piece, bytes.length - i));
        }
        return parser.finish();
    }

    @Test
    void matchers() throws Exception {
        ok("{a: 1, b: [x, 2.5]}", "{\"b\": [\"x\", 2.5], \"a\": 1}", false);
        fails("{a: 1, b: 2}", "{\"b\": 2, \"a\": 1}", true, "member order [b, a], expected [a, b]");
        fails("{a: 1}", "{\"a\": 1.0}", false, "expected 1 but was 1.0");
        fails("{a: 1}", "{\"a\": 1, \"b\": 2}", false, "$.b: unexpected member 2");
        ok("{$partial: {a: 1}}", "{\"a\": 1, \"b\": 2}", true);
        ok("{a: {$absent: true}, b: 2}", "{\"b\": 2}", false);
        fails("{a: {$absent: true}}", "{\"a\": 1}", false, "$.a: expected no value but was 1");
        ok("{id: {$type: string}, n: {$type: [integer, null]}}", "{\"id\": \"x\", \"n\": null}", false);
        ok("{$regex: '^chatcmpl-'}", "\"chatcmpl-1\"", false);
        ok("{$unordered: [2, {$type: string}, 1]}", "[1, \"s\", 2]", false);
        fails("{$unordered: [1, 1]}", "[1, 2]", false, "no element matches 1");
        ok("{$json: {a: [1]}}", "\"{\\\"a\\\": [1]}\"", false);
        ok("{$literal: {$ref: '#/x'}}", "{\"$ref\": \"#/x\"}", false);
        ok("{$one_of: [old, new]}", "\"new\"", false);
        ok("{$one_of: [1, {$type: string}]}", "\"x\"", false);
        fails("{$one_of: [old, new]}", "\"other\"", false, "matches none of 2 alternatives");
        fails("{a: {$one_of: x}}", "{\"a\": \"x\"}", false, "$one_of takes an array of alternatives");
        fails("{$nope: 1}", "1", false, "unknown matcher $nope");
    }

    private static List<String> check(String expectedYaml, String actualJson, boolean strict) throws Exception {
        Path file = Files.createTempFile("matcher", ".yaml");
        try {
            Files.writeString(file, "name: m\nvalue: " + expectedYaml + "\n");
            JsonNode expected = HttpTestCase.read(file).getFirst().content().get("value");
            return new JsonMatch(strict, JsonMatch.operators())
                    .check(expected, JsonMatch.JSON.readTree(actualJson), "$");
        } finally {
            Files.delete(file);
        }
    }

    private static void ok(String expected, String actual, boolean strict) throws Exception {
        assertEquals(List.of(), check(expected, actual, strict), expected + " vs " + actual);
    }

    private static void fails(String expected, String actual, boolean strict, String message) throws Exception {
        List<String> failures = check(expected, actual, strict);
        assertTrue(failures.stream().anyMatch(f -> f.contains(message)), failures + " should mention " + message);
    }

    /**
     * The fake service: POST /chat calls ${UPSTREAM}/complete with the request's body and streams its "parts" back as
     * OpenAI-style chunks (a tool call split in two, the usage, [DONE]); /cut stops in the middle of an event; /late
     * answers, then calls ${UPSTREAM}/late 300 ms later.
     */
    @BeforeEach
    void start() throws IOException {
        service = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        service.createContext("/chat", this::chat);
        service.createContext("/cut", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write("data: {\"a\": 1}\n\ndata: {\"b\"".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            throw new IOException("cut"); // the server drops the connection without the last chunk
        });
        service.createContext("/late", exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            String late = upstream + "/late";
            Thread.startVirtualThread(() -> {
                try (HttpClient client = HttpClient.newHttpClient()) {
                    Thread.sleep(300); // the call comes after the answer: the delay is the scenario
                    client.send(
                            HttpRequest.newBuilder(URI.create(late)).build(), HttpResponse.BodyHandlers.discarding());
                } catch (IOException | InterruptedException e) {
                    throw new IllegalStateException(e);
                }
            });
        });
        service.start();
        runner = new HttpTestRunner(server -> {
            upstream = server.path("env").path("UPSTREAM").asText();
            return URI.create("http://127.0.0.1:" + service.getAddress().getPort());
        });
    }

    @AfterEach
    void stop() {
        runner.close();
        service.stop(0);
    }

    private void chat(HttpExchange exchange) throws IOException {
        try (exchange) {
            byte[] request = exchange.getRequestBody().readAllBytes();
            HttpResponse<String> answer;
            try (HttpClient client = HttpClient.newHttpClient()) {
                answer = client.send(
                        HttpRequest.newBuilder(URI.create(upstream + "/complete?alt=sse"))
                                .header("Authorization", "Bearer t")
                                .POST(HttpRequest.BodyPublishers.ofByteArray(request))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
            } catch (InterruptedException e) {
                throw new IOException(e);
            }
            JsonNode parts = JsonMatch.JSON.readTree(answer.body()).get("parts");
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.getResponseHeaders().add("X-Trace", "1");
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            for (JsonNode part : parts) {
                write(out, "{\"choices\":[{\"delta\":{\"content\":" + part + "}}]}");
            }
            write(
                    out,
                    "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\",\"type\":\"function\","
                            + "\"function\":{\"name\":\"f\",\"arguments\":\"{\\\"x\\\":\"}}]}}]}");
            write(
                    out,
                    """
                            {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"1}"}}]}}]}""");
            write(out, "{\"choices\":[],\"usage\":{\"total_tokens\":5}}");
            out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void write(OutputStream out, String json) throws IOException {
        // split mid-event, as a transport may: the runner must not care
        byte[] event = ("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8);
        out.write(event, 0, 7);
        out.flush();
        out.write(event, 7, event.length - 7);
        out.flush();
    }

    private HttpTestCase testCase(String yaml) throws IOException {
        Path file = dir.resolve("case.yaml");
        Files.writeString(file, yaml);
        return HttpTestCase.read(file).getFirst();
    }

    private static final String CASE =
            """
            name: streamed chat
            server: {env: {UPSTREAM: '${mocks.llm.url}'}}
            request:
              method: POST
              path: /chat
              json: {prompt: hi}
            mocks:
              - server: llm
                request:
                  method: POST
                  path: /complete
                  query: {alt: sse}
                  headers: {authorization: {$regex: '^Bearer '}, x-api-key: {$absent: true}}
                  json: {prompt: hi}
                response: {status: 200, headers: {Content-Type: application/json}, json: {parts: [Hel, lo]}}
            expect:
              status: 200
              headers: {content-type: text/event-stream, x-trace: '1', x-*: {$absent: true}}
              sse:
                aggregate:
                  - {type: concat, path: choices.0.delta.content, equals: %s}
                  - type: tool_calls
                    path: choices.0.delta.tool_calls
                    equals: [{id: c1, type: function, function: {name: f, arguments: {$json: {x: 1}}}}]
                  - {type: last_value, path: usage, equals: {total_tokens: 5}}
                  - {type: last_event, equals: {data: '[DONE]'}}
                  - {type: count, equals: 6}
            """;

    @Test
    void streamedCasePasses() throws Exception {
        runner.run(testCase(CASE.formatted("Hello")));
    }

    @Test
    void failuresNameTheCaseAndEveryExpectation() throws Exception {
        String yaml = CASE.formatted("Help")
                .replace("x-trace: '1'", "x-trace: '2'")
                .replace("json: {prompt: hi}\n    ", "json: {prompt: bye}\n    ");
        AssertionError e = assertThrows(AssertionError.class, () -> runner.run(testCase(yaml)));
        String message = e.getMessage();
        assertEquals(
                """
                case streamed chat (case.yaml #1): 3 failure(s)
                  - response.headers.x-trace: expected "2" but was "1"
                  - response.sse.aggregate[0] (concat): expected "Help" but was "Hello"
                  - mocks llm#1.request.json.prompt: expected "bye" but was "hi\"""",
                message);
    }

    @Test
    void headerPatternsLeaveOnlyTheNamedHeaders() throws Exception {
        AssertionError e = assertThrows(
                AssertionError.class,
                () -> runner.run(testCase(CASE.formatted("Hello").replace(" x-trace: '1',", ""))));
        assertEquals(
                """
                case streamed chat (case.yaml #1): 1 failure(s)
                  - response.headers.x-trace: unexpected header: 1""",
                e.getMessage());
        e = assertThrows(
                AssertionError.class,
                () -> runner.run(testCase(CASE.formatted("Hello").replace("x-*: {$absent: true}", "x-*: '1'"))));
        assertEquals(
                """
                case streamed chat (case.yaml #1): 1 failure(s)
                  - response.headers.x-*: a header pattern takes only {$absent: true}""",
                e.getMessage());
    }

    @Test
    void callAfterTheAnswer() throws Exception {
        runner.run(
                testCase(
                        """
                name: late call
                server: {env: {UPSTREAM: '${mocks.llm.url}'}}
                request: {method: GET, path: /late}
                mocks:
                  - server: llm
                    request: {method: GET, path: /late}
                    response: {status: 204}
                expect: {status: 204}
                """));
    }

    @Test
    void unexpectedAndMissingCalls() throws Exception {
        String extra = CASE.formatted("Hello")
                .replace(
                        "expect:",
                        """
                  - server: llm
                    request: {method: GET, path: /never}
                    response: {status: 204}
                expect:""");
        AssertionError e = assertThrows(AssertionError.class, () -> runner.run(testCase(extra)));
        assertEquals(
                """
                case streamed chat (case.yaml #1): 1 failure(s)
                  - mocks llm#2: never called (GET /never)""",
                e.getMessage());

        String none = CASE.formatted("Hello").replaceAll("(?s)mocks:.*?expect:", "mocks: []\nexpect:");
        e = assertThrows(AssertionError.class, () -> runner.run(testCase(none)));
        assertTrue(
                e.getMessage().contains("mocks llm call #1 (POST /complete): unexpected, only 0 declared for llm"),
                e.getMessage());

        String any = none.replace("server: {env", "options: {mock_order: any}\nserver: {env");
        e = assertThrows(AssertionError.class, () -> runner.run(testCase(any)));
        assertTrue(
                e.getMessage().contains("mocks llm call #1 (POST /complete): matches no unused mock"), e.getMessage());
    }

    @Test
    void truncatedAndIncompleteStreams() throws Exception {
        runner.run(
                testCase(
                        """
                name: cut
                request: {method: GET, path: /cut}
                expect:
                  status: 200
                  sse: {termination: truncated, events: [{json: {a: 1}}]}
                """));
        AssertionError e = assertThrows(
                AssertionError.class,
                () -> runner.run(
                        testCase(
                                """
                name: cut, expected whole
                request: {method: GET, path: /cut}
                expect: {sse: {events: [{json: {a: 1}}]}}
                """)));
        assertEquals(
                """
                case cut, expected whole (case.yaml #1): 1 failure(s)
                  - response.sse.termination: expected complete but the stream was truncated""",
                e.getMessage());
    }

    @Test
    void placeholdersAndTheirEscape() throws Exception {
        JsonNode[] seen = new JsonNode[1];
        HttpTestRunner capture = new HttpTestRunner(server -> {
            seen[0] = server;
            return URI.create("http://127.0.0.1:" + service.getAddress().getPort());
        });
        try {
            capture.run(
                    testCase(
                            """
                    name: placeholders
                    server: {env: {A: '${mocks.one.url}/v1', B: '$${mocks.one.url}'}}
                    request: {method: GET, path: /cut}
                    expect: {sse: {termination: truncated}}
                    """));
        } finally {
            capture.close();
        }
        assertTrue(seen[0].at("/env/A").asText().matches("http://127\\.0\\.0\\.1:\\d+/v1"), seen[0].toString());
        assertEquals("${mocks.one.url}", seen[0].at("/env/B").asText());
    }
}
