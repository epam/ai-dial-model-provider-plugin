package com.epam.aidial.provider.api;

import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;

/** The transport adds the framing headers. */
public record AdapterResponse(int status, MultiMap headers, Body body) {

    public static AdapterResponse internalServerError() {
        return text(500, "Internal Server Error");
    }

    public static AdapterResponse json(int status, String json) {
        return bytes(status, "application/json", json);
    }

    public static AdapterResponse text(int status, String text) {
        return bytes(status, "text/plain; charset=utf-8", text);
    }

    private static AdapterResponse bytes(int status, String contentType, String content) {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap().set("Content-Type", contentType);
        return new AdapterResponse(status, headers, new Body.Bytes(Buffer.buffer(content)));
    }
}
