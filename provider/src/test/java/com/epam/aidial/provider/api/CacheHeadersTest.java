package com.epam.aidial.provider.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.epam.aidial.provider.api.CacheHeaders.MessagesCacheHeaders;
import io.vertx.core.MultiMap;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CacheHeadersTest {

    private static final MessagesCacheHeaders EXAMPLE =
            new MessagesCacheHeaders(1, 2, true, List.of(new int[] {0}, new int[] {-1, 1, 2}, new int[] {3, 4}));

    private static String body(String member) {
        return "prefix.body." + member;
    }

    private static Optional<String> toResponses(MessagesCacheHeaders headers, String member) {
        return MessagesCachePath.parse(body(member))
                .flatMap(headers::toResponses)
                .map(ResponsesCachePath::value);
    }

    private static Optional<String> toMessages(MessagesCacheHeaders headers, String member) {
        return ResponsesCachePath.parse(body(member))
                .flatMap(headers::toMessages)
                .map(MessagesCachePath::value);
    }

    @Test
    void toolIsTheToolWhenThereIsOne() {
        assertEquals(Optional.of(body("tools[0]")), toResponses(EXAMPLE, "tools[0]"));
        assertEquals(Optional.empty(), toResponses(EXAMPLE, "tools[1]"));
        assertEquals(Optional.of(body("tools[0]")), toMessages(EXAMPLE, "tools[0]"));
        assertEquals(Optional.empty(), toMessages(EXAMPLE, "tools[1]"));
    }

    @Test
    void everySystemBlockIsTheInstructionsAndTheInstructionsAreTheLast() {
        assertEquals(Optional.of(body("instructions[0]")), toResponses(EXAMPLE, "system[0]"));
        assertEquals(Optional.of(body("instructions[0]")), toResponses(EXAMPLE, "system[1]"));
        assertEquals(Optional.empty(), toResponses(EXAMPLE, "system[2]"));
        assertEquals(Optional.of(body("system[1]")), toMessages(EXAMPLE, "instructions[0]"));
        assertEquals(Optional.empty(), toMessages(EXAMPLE, "instructions[1]"));
    }

    @Test
    void blockIsItsItemAndItemIsItsLastBlock() {
        assertEquals(Optional.of(body("input[1]")), toResponses(EXAMPLE, "messages[1].content[1]"));
        assertEquals(Optional.of(body("input[2]")), toResponses(EXAMPLE, "messages[1].content[2]"));
        assertEquals(Optional.of(body("input[3]")), toResponses(EXAMPLE, "messages[2].content[0]"));
        assertEquals(Optional.of(body("input[4]")), toResponses(EXAMPLE, "messages[2].content[1]"));
        assertEquals(Optional.of(body("messages[0].content[0]")), toMessages(EXAMPLE, "input[0]"));
        assertEquals(Optional.of(body("messages[1].content[1]")), toMessages(EXAMPLE, "input[1]"));
        assertEquals(Optional.of(body("messages[1].content[2]")), toMessages(EXAMPLE, "input[2]"));
        assertEquals(Optional.of(body("messages[2].content[0]")), toMessages(EXAMPLE, "input[3]"));
        assertEquals(Optional.of(body("messages[2].content[1]")), toMessages(EXAMPLE, "input[4]"));
    }

    @Test
    void droppedBlockIsNearestKeptBlockBeforeIt() {
        assertEquals(Optional.of(body("input[0]")), toResponses(EXAMPLE, "messages[1].content[0]"));
    }

    @Test
    void droppedBlockWithNothingBeforeItFallsBackToInstructionsThenLastTool() {
        List<int[]> dropped = List.of(new int[] {-1}, new int[] {0});
        assertEquals(
                Optional.of(body("instructions[0]")),
                toResponses(new MessagesCacheHeaders(1, 1, true, dropped), "messages[0].content[0]"));
        assertEquals(
                Optional.of(body("tools[1]")),
                toResponses(new MessagesCacheHeaders(2, 0, false, dropped), "messages[0].content[0]"));
        assertEquals(
                Optional.empty(),
                toResponses(new MessagesCacheHeaders(0, 0, false, dropped), "messages[0].content[0]"));
    }

    @Test
    void systemWithoutInstructionsIsNothing() {
        MessagesCacheHeaders emptySystem = new MessagesCacheHeaders(0, 1, false, List.of(new int[] {0}));
        assertEquals(Optional.empty(), toResponses(emptySystem, "system[0]"));
        assertEquals(Optional.empty(), toMessages(emptySystem, "instructions[0]"));
    }

    @Test
    void anItemNoBlockWentIntoIsNothing() {
        assertEquals(Optional.empty(), toMessages(EXAMPLE, "input[5]"));
    }

    @Test
    void whatIsNoPathOfTheBodyIsNothing() {
        for (String member : new String[] {
            "metadata", "messages[9].content[0]", "messages[0].content[7]", "messages[x].content[0]", "input[0]"
        }) {
            assertEquals(Optional.empty(), toResponses(EXAMPLE, member), member);
        }
        for (String member : new String[] {"input", "input[-1]", "messages[0].content[0]", "output[0]"}) {
            assertEquals(Optional.empty(), toMessages(EXAMPLE, member), member);
        }
        assertEquals(Optional.empty(), MessagesCachePath.parse("body.tools[0]"));
        assertEquals(Optional.empty(), ResponsesCachePath.parse(""));
    }

    @Test
    void messagesPathIsParsedByWhatItAddresses() {
        assertEquals(Optional.of(new MessagesCachePath.Tool(2)), MessagesCachePath.parse(body("tools[2]")));
        assertEquals(Optional.of(new MessagesCachePath.SystemBlock(1)), MessagesCachePath.parse(body("system[1]")));
        assertEquals(
                Optional.of(new MessagesCachePath.MessageBlock(3, 4)),
                MessagesCachePath.parse(body("messages[3].content[4]")));
        for (String member : new String[] {"input[0]", "instructions[0]", "messages[3]", "tools[99999999999]"}) {
            assertEquals(Optional.empty(), MessagesCachePath.parse(body(member)), member);
        }
    }

    @Test
    void responsesPathIsParsedByWhatItAddresses() {
        assertEquals(Optional.of(new ResponsesCachePath.Tool(2)), ResponsesCachePath.parse(body("tools[2]")));
        assertEquals(
                Optional.of(new ResponsesCachePath.Instructions()), ResponsesCachePath.parse(body("instructions[0]")));
        assertEquals(Optional.of(new ResponsesCachePath.InputItem(5)), ResponsesCachePath.parse(body("input[5]")));
        for (String member : new String[] {"instructions[1]", "system[0]", "input", "input[99999999999]"}) {
            assertEquals(Optional.empty(), ResponsesCachePath.parse(body(member)), member);
        }
    }

    @Test
    void pathsAreWrittenInTheFormOfDialCore() {
        assertEquals(body("tools[2]"), new MessagesCachePath.Tool(2).value());
        assertEquals(body("system[1]"), new MessagesCachePath.SystemBlock(1).value());
        assertEquals(body("messages[3].content[4]"), new MessagesCachePath.MessageBlock(3, 4).value());
        assertEquals(body("tools[2]"), new ResponsesCachePath.Tool(2).value());
        assertEquals(body("instructions[0]"), new ResponsesCachePath.Instructions().value());
        assertEquals(body("input[5]"), new ResponsesCachePath.InputItem(5).value());
    }

    @Test
    void headerNamesAreTheDialExtensionNames() {
        assertEquals("X-Dial-Cache-Breakpoint-Path", CacheHeaders.BREAKPOINT);
        assertEquals("X-Dial-Cache-Expire-At", CacheHeaders.EXPIRE_AT);
        assertTrue(CacheHeaders.isCacheHeader("X-Dial-Cache-Policy"));
        assertTrue(CacheHeaders.isCacheHeader("x-dial-cache-expire-at"));
        assertFalse(CacheHeaders.isCacheHeader("x-dial-other"));
    }

    @Test
    void askedWhenTheRequestHasTheBreakpointHeader() {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        assertFalse(CacheHeaders.asked(headers));

        headers.set("x-dial-cache-breakpoint-path", "");

        assertTrue(CacheHeaders.asked(headers));
    }

    @Test
    void stampWritesTheHeadersInOrder() {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap().set("Content-Type", "application/json");

        CacheHeaders.stamp(
                headers, new CacheBreakpoint(BodyPath.ROOT.member("input").index(0), 600), Instant.ofEpochSecond(4000));

        assertEquals("prefix.body.input[0]", headers.get("x-dial-cache-breakpoint-path"));
        assertEquals("4600", headers.get("x-dial-cache-expire-at"));
        assertEquals(
                List.of("Content-Type", "X-Dial-Cache-Breakpoint-Path", "X-Dial-Cache-Expire-At"),
                headers.names().stream().toList());
    }

    @Test
    void stampReplacesWhatTheHeadersHold() {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap()
                .add("x-dial-cache-breakpoint-path", "upstream")
                .add("x-dial-cache-expire-at", "1");

        CacheHeaders.stamp(
                headers, new CacheBreakpoint(new BodyPath("mine"), 100), Instant.ofEpochSecond(1_899_999_900L));

        assertEquals(List.of("mine"), headers.getAll(CacheHeaders.BREAKPOINT));
        assertEquals(List.of("1900000000"), headers.getAll(CacheHeaders.EXPIRE_AT));
    }

    @Test
    void toUpstreamCarriesThePathOverAndLeavesOutWhatNamesNothing() {
        MultiMap carried =
                MultiMap.caseInsensitiveMultiMap().set(CacheHeaders.BREAKPOINT, body("messages[1].content[1]"));
        EXAMPLE.toUpstream(carried);
        assertEquals(body("input[1]"), carried.get(CacheHeaders.BREAKPOINT));

        MultiMap nothing =
                MultiMap.caseInsensitiveMultiMap().set(CacheHeaders.BREAKPOINT, body("messages[9].content[0]"));
        EXAMPLE.toUpstream(nothing);
        assertNull(nothing.get(CacheHeaders.BREAKPOINT));

        MultiMap none = MultiMap.caseInsensitiveMultiMap();
        EXAMPLE.toUpstream(none);
        assertTrue(none.isEmpty());
    }

    @Test
    void toClientCarriesThePathBackWithItsExpiry() {
        MultiMap upstream = MultiMap.caseInsensitiveMultiMap()
                .set(CacheHeaders.BREAKPOINT, body("input[2]"))
                .set(CacheHeaders.EXPIRE_AT, "1300");
        MultiMap client = MultiMap.caseInsensitiveMultiMap();

        EXAMPLE.toClient(upstream, client);

        assertEquals(body("messages[1].content[2]"), client.get(CacheHeaders.BREAKPOINT));
        assertEquals("1300", client.get(CacheHeaders.EXPIRE_AT));
    }

    @Test
    void toClientLeavesOutThePathThatNamesNothingWithItsExpiry() {
        MultiMap upstream = MultiMap.caseInsensitiveMultiMap()
                .set(CacheHeaders.BREAKPOINT, body("input[5]"))
                .set(CacheHeaders.EXPIRE_AT, "1300");
        MultiMap client = MultiMap.caseInsensitiveMultiMap();

        EXAMPLE.toClient(upstream, client);

        assertTrue(client.isEmpty());
    }
}
