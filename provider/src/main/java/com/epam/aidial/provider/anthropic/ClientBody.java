package com.epam.aidial.provider.anthropic;

import com.epam.aidial.provider.api.CacheBreakpoint;
import com.epam.aidial.provider.api.InvalidRequestException;
import com.epam.aidial.provider.json.BodyFields;
import com.epam.aidial.provider.json.Json;
import com.epam.aidial.provider.json.RequestBody;
import org.jspecify.annotations.Nullable;

/**
 * The cache_control candidates ({@link CacheBreakpoints}) are collected in the same parse: a byte scan for
 * "cache_control" first cost more than the walk it saved, and missed an escaped name.
 *
 * @param bytes the body as received, less a BOM
 * @param breakpoint null for any other endpoint or when the request carries none
 * @param stream false for any other endpoint than /v1/messages
 */
record ClientBody(byte[] bytes, BodyFields fields, @Nullable CacheBreakpoint breakpoint, String model, boolean stream) {

    /**
     * @throws InvalidRequestException when the body is not a JSON object, or its model or stream is invalid
     */
    static ClientBody parse(byte[] received, Endpoint endpoint) {
        boolean messages = endpoint == Endpoint.MESSAGES;
        CacheBreakpoints cache = messages ? new CacheBreakpoints() : null;
        RequestBody parsed = RequestBody.parse(received, cache);
        BodyFields fields = parsed.fields();
        String model = model(fields);
        boolean stream = messages && Boolean.TRUE.equals(Json.getBool(fields.get("stream"), "stream"));
        // Before any member changes: the breakpoint is where the client put it.
        CacheBreakpoint breakpoint = cache == null ? null : cache.breakpoint(fields);
        return new ClientBody(parsed.bytes(), fields, breakpoint, model, stream);
    }

    /** Required: the upstream cannot be named without it, and Vertex AI and Bedrock take it in their URL. */
    private static String model(BodyFields fields) {
        Object value = Json.parseOrNull(fields.get("model")); // null: absent, or a JSON null
        if (value == null) {
            throw new InvalidRequestException("model: Field required");
        }
        if (!(value instanceof String model)) {
            throw new InvalidRequestException("model: Input should be a valid string");
        }
        if (model.isEmpty()) {
            throw new InvalidRequestException("model: String should have at least 1 character");
        }
        return model;
    }
}
