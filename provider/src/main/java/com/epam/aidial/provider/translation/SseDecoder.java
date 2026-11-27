package com.epam.aidial.provider.translation;

import io.vertx.core.buffer.Buffer;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The events of a server-sent event stream, as the HTML standard reads it, from chunks that end anywhere, inside a
 * line, inside a multi-byte character, between a CR and its LF. Lines end at CRLF, LF or CR; an event ends at an empty
 * line, its {@code data} lines joined by a line feed; a comment (a line that starts with a colon) is a
 * {@link SseEvent#heartbeat() heartbeat} where it stands, which is how a server says it is alive. The other fields
 * ({@code event}, {@code id}, {@code retry}) say nothing the translation needs. {@code [DONE]} is not an event. What
 * is left when the stream ends, an event without its empty line, is dropped, as the standard says.
 */
final class SseDecoder {

    /** An event's data, or a heartbeat (no data). */
    record SseEvent(boolean isHeartbeat, String data) {

        private static final SseEvent HEARTBEAT = new SseEvent(true, "");

        static SseEvent heartbeat() {
            return HEARTBEAT;
        }
    }

    private final ByteArrayOutputStream line = new ByteArrayOutputStream();
    private final StringBuilder data = new StringBuilder();
    private boolean hasData;
    private boolean afterCr;

    List<SseEvent> feed(Buffer chunk) {
        return feed(chunk.getBytes());
    }

    List<SseEvent> feed(byte[] bytes) {
        List<SseEvent> events = new ArrayList<>();
        for (byte b : bytes) {
            if (afterCr) {
                afterCr = false;
                if (b == '\n') {
                    continue;
                }
            }
            if (b == '\n' || b == '\r') {
                afterCr = b == '\r';
                endLine(events);
            } else {
                line.write(b);
            }
        }
        return events;
    }

    private void endLine(List<SseEvent> events) {
        String text = line.toString(StandardCharsets.UTF_8);
        line.reset();
        if (text.isEmpty()) {
            if (hasData) {
                String event = data.toString();
                if (!event.startsWith("[DONE]")) {
                    events.add(new SseEvent(false, event));
                }
            }
            data.setLength(0);
            hasData = false;
        } else if (text.charAt(0) == ':') {
            events.add(SseEvent.heartbeat());
        } else {
            int colon = text.indexOf(':');
            String name = colon < 0 ? text : text.substring(0, colon);
            if (name.equals("data")) {
                String value = colon < 0 ? "" : text.substring(colon + 1);
                if (hasData) {
                    data.append('\n');
                }
                data.append(value.startsWith(" ") ? value.substring(1) : value);
                hasData = true;
            }
        }
    }
}
