package com.epam.aidial.provider.openai;

import com.epam.aidial.provider.api.AdapterResponse;
import com.epam.aidial.provider.json.Json;
import com.epam.aidial.provider.upstream.AdapterErrors;
import com.epam.aidial.provider.upstream.Failure;
import io.vertx.core.Future;
import java.util.LinkedHashMap;
import java.util.Map;

final class OpenAiErrors {

    private OpenAiErrors() {}

    private static AdapterResponse error(int status, String message, String type) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("message", truncateMiddle(message, 1024)); // in case anything sensitive leaked into it
        e.put("type", type);
        e.put("code", Integer.toString(status));
        return AdapterResponse.json(status, Json.encode(Map.of("error", e)));
    }

    record Failures(int maxConnections) implements AdapterErrors {

        private static final String INTERNAL_TYPE = "internal_server_error";

        Future<AdapterResponse> answer(Throwable e, String path) {
            return Failure.answer(e, path, this);
        }

        @Override
        public AdapterResponse invalidRequest(String message) {
            return error(400, message, "invalid_request_error");
        }

        @Override
        public AdapterResponse tokenUnavailable() {
            return error(401, AUTHENTICATION_FAILED, "Unauthorized");
        }

        @Override
        public AdapterResponse misconfigured(String message) {
            return error(500, message, INTERNAL_TYPE);
        }

        @Override
        public AdapterResponse badGateway(String message) {
            return error(502, message, INTERNAL_TYPE);
        }

        @Override
        public AdapterResponse awsRoleUnavailable() {
            return misconfigured(AWS_ROLE_UNAVAILABLE);
        }

        @Override
        public AdapterResponse awsCredentialsUnavailable() {
            return misconfigured(AWS_CREDENTIALS_UNAVAILABLE);
        }

        @Override
        public AdapterResponse poolExhausted() {
            return error(503, POOL_EXHAUSTED + " (HTTP_MAX_CONNECTIONS=" + maxConnections + ")", INTERNAL_TYPE);
        }

        @Override
        public AdapterResponse timeout() {
            return error(504, TIMEOUT, "timeout");
        }

        @Override
        public AdapterResponse internalError() {
            return misconfigured(INTERNAL_ERROR);
        }

        @Override
        public AdapterResponse connectionFailed() {
            return error(502, "Error communicating with OpenAI", "connection");
        }
    }

    static String truncateMiddle(String s, int limit) {
        int[] cps = s.codePoints().toArray();
        int excess = cps.length - limit;
        if (excess <= 0) {
            return s;
        }
        return new String(cps, 0, limit / 2) + "<truncated " + excess + " characters>"
                + new String(cps, cps.length - (limit - limit / 2), limit - limit / 2);
    }
}
