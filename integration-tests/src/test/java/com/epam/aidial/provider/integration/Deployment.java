package com.epam.aidial.provider.integration;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * One model reached one way through the service: what DIAL Core would send for it. The tests and the smoke report
 * ask it questions; they know nothing of the headers that choose the upstream and its credentials.
 *
 * @param via     what is under test besides the model: the Claude client, or the way Azure is authorized
 * @param path    the route of the service
 * @param headers X-Upstream-Endpoint, X-Upstream-Key and X-Upstream-Extra-Data, as DIAL Core sets them
 */
record Deployment(Cloud cloud, Api api, String via, String model, String path, Map<String, String> headers) {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Set<Integer> RETRIED = Set.of(429, 503, 529);
    private static final int ATTEMPTS = 3;

    /** The answer to a question; an AssertionError that says what the service answered, when it is no answer. */
    Answer ask(String question) {
        return ask(question, null);
    }

    /** The answer to a question that the model must answer by calling the tool. */
    Answer ask(String question, Tool tool) {
        Reply reply = tryAsk(question, tool);
        if (!reply.ok()) {
            throw new AssertionError("%s: HTTP %d: %s".formatted(this, reply.status(), reply.reason()));
        }
        return api.read(reply.json());
    }

    /** The service's reply to a question, whatever it is. */
    Reply tryAsk(String question, Tool tool) {
        String body = api.request(model, question, tool);
        Reply reply = null;
        for (int attempt = 1; attempt <= ATTEMPTS && (reply == null || RETRIED.contains(reply.status())); attempt++) {
            sleep(attempt == 1 ? 0 : 3000L * attempt);
            reply = post(body);
        }
        return reply;
    }

    private Reply post(String body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(Service.url() + path))
                .timeout(Duration.ofMinutes(3))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(request::header);
        try {
            HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new Reply(response.statusCode(), response.body());
        } catch (IOException e) {
            return new Reply(0, "no answer: " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Reply(0, "interrupted");
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public String toString() {
        return "%s %s via %s: %s".formatted(cloud, api.name().toLowerCase(java.util.Locale.ROOT), via, model);
    }
}
