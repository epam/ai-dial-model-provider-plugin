package com.epam.aidial.provider.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import org.junit.jupiter.api.Test;

class AdapterResponseTest {

    @Test
    void cacheHeadersAreTheDialExtensionNames() {
        assertEquals("X-Dial-Cache-Breakpoint-Path", AdapterResponse.CACHE_BREAKPOINT_HEADER);
        assertEquals("X-Dial-Cache-Expire-At", AdapterResponse.CACHE_EXPIRE_AT_HEADER);
    }

    @Test
    void settersWriteTheHeadersInOrderAndChain() {
        AdapterResponse response = AdapterResponse.json(200, "{}");

        assertSame(
                response,
                response.setCacheBreakpoint(BodyPath.ROOT.member("input").index(0))
                        .setCacheExpireAt(4600));

        assertEquals("prefix.body.input[0]", response.headers().get("x-dial-cache-breakpoint-path"));
        assertEquals("4600", response.headers().get("x-dial-cache-expire-at"));
        assertEquals(
                List.of("Content-Type", "X-Dial-Cache-Breakpoint-Path", "X-Dial-Cache-Expire-At"),
                response.headers().names().stream().toList());
    }

    @Test
    void settersReplaceWhatTheHeadersHold() {
        AdapterResponse response = AdapterResponse.json(200, "{}");
        response.headers().add("x-dial-cache-breakpoint-path", "upstream").add("x-dial-cache-expire-at", "1");

        response.setCacheBreakpoint(new BodyPath("mine")).setCacheExpireAt(1_900_000_000L);

        assertEquals(List.of("mine"), response.headers().getAll(AdapterResponse.CACHE_BREAKPOINT_HEADER));
        assertEquals(List.of("1900000000"), response.headers().getAll(AdapterResponse.CACHE_EXPIRE_AT_HEADER));
    }
}
