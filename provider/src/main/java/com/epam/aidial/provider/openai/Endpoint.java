package com.epam.aidial.provider.openai;

import io.vertx.core.http.HttpMethod;

enum Endpoint {
    CREATE(HttpMethod.POST, "", false, true),
    INPUT_TOKENS(HttpMethod.POST, "/input_tokens", false, true),
    RETRIEVE(HttpMethod.GET, "", true, false),
    DELETE(HttpMethod.DELETE, "", true, false),
    CANCEL(HttpMethod.POST, "/cancel", true, false);

    private final HttpMethod method;
    private final String suffix;
    private final boolean byId;
    private final boolean hasBody;

    Endpoint(HttpMethod method, String suffix, boolean byId, boolean hasBody) {
        this.method = method;
        this.suffix = suffix;
        this.byId = byId;
        this.hasBody = hasBody;
    }

    HttpMethod method() {
        return method;
    }

    String suffix() {
        return suffix;
    }

    boolean byId() {
        return byId;
    }

    boolean hasBody() {
        return hasBody;
    }
}
