package com.epam.aidial.provider.anthropic;

import com.epam.aidial.provider.api.AdapterResponse;
import com.epam.aidial.provider.json.Json;
import com.epam.aidial.provider.upstream.AdapterErrors;
import com.epam.aidial.provider.upstream.Failure;
import io.vertx.core.Future;
import java.util.LinkedHashMap;
import java.util.Map;

/** The details of a failure are logged, never sent to the client. */
final class AnthropicErrors {

    private static final String API_ERROR = "api_error";

    private AnthropicErrors() {}

    private static AdapterResponse error(int status, String type, String message) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("message", message);
        e.put("type", type);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", e);
        body.put("type", "error");
        return AdapterResponse.json(status, Json.encode(body) + "\n");
    }

    static final class Failures implements AdapterErrors {

        Future<AdapterResponse> answer(Throwable e, String path) {
            return Failure.answer(e, path, this);
        }

        AdapterResponse endpointNotSupported() {
            return error(404, "not_found_error", "Endpoint not supported");
        }

        @Override
        public AdapterResponse invalidRequest(String message) {
            return error(400, "invalid_request_error", message);
        }

        @Override
        public AdapterResponse tokenUnavailable() {
            return error(401, "authentication_error", AUTHENTICATION_FAILED);
        }

        /** Plain-text 500: no detail of the settings may reach the client. */
        @Override
        public AdapterResponse misconfigured(String message) {
            return plainInternalError();
        }

        @Override
        public AdapterResponse badGateway(String message) {
            return error(502, API_ERROR, message);
        }

        @Override
        public AdapterResponse awsRoleUnavailable() {
            return error(500, API_ERROR, AWS_ROLE_UNAVAILABLE);
        }

        @Override
        public AdapterResponse awsCredentialsUnavailable() {
            return error(500, API_ERROR, AWS_CREDENTIALS_UNAVAILABLE);
        }

        @Override
        public AdapterResponse poolExhausted() {
            return error(503, API_ERROR, POOL_EXHAUSTED);
        }

        @Override
        public AdapterResponse timeout() {
            return error(500, API_ERROR, TIMEOUT);
        }

        @Override
        public AdapterResponse internalError() {
            return error(500, API_ERROR, INTERNAL_ERROR);
        }

        /** Not a connection-specific message: the OpenAI route's one names OpenAI. */
        @Override
        public AdapterResponse connectionFailed() {
            return internalError();
        }
    }
}
