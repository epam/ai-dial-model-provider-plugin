package com.epam.aidial.provider.translation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.epam.aidial.provider.translation.SseDecoder.SseEvent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What the cases cannot send: a stream cut into chunks anywhere. */
class SseDecoderTest {

    private static final SseEvent PING = SseEvent.heartbeat();

    private static SseEvent data(String data) {
        return new SseEvent(false, data);
    }

    /** The events of the stream fed whole, and fed a byte at a time: they must be the same. */
    private static List<SseEvent> decode(String stream) {
        byte[] bytes = stream.getBytes(StandardCharsets.UTF_8);
        List<SseEvent> whole = new SseDecoder().feed(bytes);
        SseDecoder decoder = new SseDecoder();
        List<SseEvent> byByte = new ArrayList<>();
        for (byte b : bytes) {
            byByte.addAll(decoder.feed(new byte[] {b}));
        }
        assertEquals(whole, byByte, "split at every byte");
        for (int cut = 1; cut < bytes.length; cut++) {
            SseDecoder two = new SseDecoder();
            List<SseEvent> events = new ArrayList<>(two.feed(java.util.Arrays.copyOfRange(bytes, 0, cut)));
            events.addAll(two.feed(java.util.Arrays.copyOfRange(bytes, cut, bytes.length)));
            assertEquals(whole, events, "cut at " + cut);
        }
        return whole;
    }

    @Test
    void eventEndsAtAnEmptyLine() {
        assertEquals(List.of(data("{\"a\":1}"), data("b")), decode("data: {\"a\":1}\n\ndata: b\n\n"));
    }

    @Test
    void lineEndsAreLfCrLfAndCr() {
        assertEquals(List.of(data("a"), data("b"), data("c")), decode("data: a\n\ndata: b\r\n\r\ndata: c\r\r"));
    }

    @Test
    void dataLinesAreJoinedByLineFeed() {
        assertEquals(List.of(data("one\ntwo\n\nfour")), decode("data: one\ndata:two\ndata:\ndata: four\n\n"));
    }

    @Test
    void oneSpaceAfterTheColonIsDropped() {
        assertEquals(List.of(data(" two spaces"), data("none")), decode("data:  two spaces\n\ndata:none\n\n"));
    }

    @Test
    void otherFieldsSayNothing() {
        assertEquals(
                List.of(data("{}")), decode("event: response.created\nid: 7\nretry: 100\ndata: {}\nunknown: x\n\n"));
    }

    @Test
    void commentIsHeartbeatWhereItStands() {
        // the comment inside an event is said at once; the event is said when it ends
        assertEquals(List.of(PING, PING, data("a")), decode(": ping\n\ndata: a\n: ping\n\n"));
    }

    @Test
    void doneIsNoEvent() {
        assertEquals(List.of(data("a")), decode("data: a\n\ndata: [DONE]\n\n"));
    }

    @Test
    void multiByteCharactersSurviveAnyCut() {
        assertEquals(List.of(data("héllo ✓ 😀")), decode("data: héllo ✓ 😀\n\n"));
    }

    @Test
    void eventWithoutItsEmptyLineIsDropped() {
        assertEquals(List.of(data("a")), decode("data: a\n\ndata: unfinished"));
        assertEquals(List.of(), decode("data: unfinished\n"));
    }

    @Test
    void emptyLinesAloneAreNothing() {
        assertEquals(List.of(), decode("\n\n\r\n\r\n"));
    }
}
