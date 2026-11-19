package com.epam.aidial.provider.api;

import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import java.time.Instant;

/** The transport adds the framing headers. */
public record AdapterResponse(int status, MultiMap headers, Body body) {

    public static final String CACHE_BREAKPOINT_HEADER = "X-Dial-Cache-Breakpoint-Path";

    /** The epoch second at which the cached prompt expires. */
    public static final String CACHE_EXPIRE_AT_HEADER = "X-Dial-Cache-Expire-At";

    public AdapterResponse setCacheBreakpoint(BodyPath path) {
        headers.set(CACHE_BREAKPOINT_HEADER, path.value());
        return this;
    }

    public AdapterResponse setCacheBreakpoint(CacheBreakpoint breakpoint, Instant now) {
        return setCacheBreakpoint(breakpoint.path()).setCacheExpireAt(now.getEpochSecond() + breakpoint.ttlSeconds());
    }

    public AdapterResponse setCacheExpireAt(long epochSeconds) {
        headers.set(CACHE_EXPIRE_AT_HEADER, Long.toString(epochSeconds));
        return this;
    }

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
