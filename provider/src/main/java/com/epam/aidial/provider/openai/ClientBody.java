package com.epam.aidial.provider.openai;

import com.epam.aidial.provider.api.BodyPath;
import com.epam.aidial.provider.api.CacheBreakpoint;
import com.epam.aidial.provider.api.InvalidRequestException;
import com.epam.aidial.provider.json.BodyFields;
import com.epam.aidial.provider.json.Json;
import com.epam.aidial.provider.json.RequestBody;
import org.jspecify.annotations.Nullable;

record ClientBody(byte @Nullable [] bytes, @Nullable BodyFields fields, @Nullable String model, boolean stream) {

    /** Azure OpenAI clears a prompt cache within 5 to 10 minutes of inactivity. */
    static final int CACHE_TTL_SECONDS = 5 * 60;

    static final ClientBody NONE = new ClientBody(null, null, null, false);

    /**
     * @throws InvalidRequestException when the body is not a JSON object or model/stream have the wrong type
     */
    static ClientBody parse(byte[] received, Endpoint endpoint) {
        if (!endpoint.hasBody()) {
            return NONE;
        }
        RequestBody body = RequestBody.parse(received, null);
        BodyFields fields = body.fields();
        String model = Json.getStr(fields.get("model"), "model");
        // Missing or null is false.
        boolean stream =
                endpoint == Endpoint.CREATE && Boolean.TRUE.equals(Json.getBool(fields.get("stream"), "stream"));
        return new ClientBody(body.bytes(), fields, model, stream);
    }

    /** Computed only when asked for: it walks the input. */
    @Nullable
    CacheBreakpoint breakpoint() {
        BodyPath path = fields == null ? null : breakpointPath(fields);
        return path == null ? null : new CacheBreakpoint(path, CACHE_TTL_SECONDS);
    }

    /** A scalar input counts as a one-element array, as DIAL Core hashes it. */
    static @Nullable BodyPath breakpointPath(BodyFields fields) {
        if (!fields.isNull("input")) {
            int n = fields.arrayLength("input");
            if (n < 0) {
                return BodyPath.ROOT.member("input").index(0);
            }
            if (n > 0) {
                return BodyPath.ROOT.member("input").index(n - 1);
            }
        }
        return BodyFields.truthy(fields.get("instructions"))
                ? BodyPath.ROOT.member("instructions").index(0)
                : null;
    }
}
