package com.epam.aidial.provider.openai;

import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpMethod;

final class OpenAiHeaders {

    private OpenAiHeaders() {}

    /**
     * Credentials are added later, by {@link UpstreamAuth}. No Accept-Encoding goes upstream, so the body comes
     * decoded.
     */
    static MultiMap toUpstream(Target target, Endpoint endpoint) {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap().addAll(target.headers());
        if (endpoint.method() == HttpMethod.GET) {
            headers.remove("Content-Type");
        } else {
            headers.set("Content-Type", "application/json");
        }
        headers.set("Accept", "application/json");
        return headers;
    }

    static MultiMap errorToClient(MultiMap upstream) {
        return MultiMap.caseInsensitiveMultiMap().addAll(upstream);
    }

    /** A Content-Encoding the upstream sends anyway stays with the bytes it describes. */
    static MultiMap successToClient(MultiMap upstream) {
        return errorToClient(upstream).remove("Content-Length");
    }
}
