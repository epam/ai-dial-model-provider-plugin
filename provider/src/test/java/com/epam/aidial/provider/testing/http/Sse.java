package com.epam.aidial.provider.testing.http;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The WHATWG event-stream interpretation, fed in whatever pieces the transport delivers: a CR at the end of one
 * piece and an LF at the start of the next are one line end, and a stream that ends inside an event leaves it
 * {@link Parser#incomplete() incomplete} (the standard discards it).
 */
public final class Sse {

    private Sse() {}

    /** Type is "message" unless set; data is the lines joined by LF; the id and retry are null if none. */
    public record Event(String event, String data, String id, Integer retry) {}

    public static final class Parser {
        private final List<Event> events = new ArrayList<>();
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();
        private boolean afterCr;
        private boolean first = true;
        private boolean inEvent;
        private String type;
        private final StringBuilder data = new StringBuilder();
        private boolean hasData;
        private String lastId;
        private Integer retry;
        private boolean incomplete;

        public void feed(byte[] bytes, int offset, int length) {
            for (int i = offset; i < offset + length; i++) {
                byte b = bytes[i];
                if (b == '\n' && afterCr) {
                    afterCr = false;
                    continue;
                }
                afterCr = b == '\r';
                if (b == '\r' || b == '\n') {
                    line(line.toString(StandardCharsets.UTF_8));
                    line.reset();
                } else {
                    line.write(b);
                }
            }
        }

        /** The events dispatched; a partial last line or event is discarded and marks the stream incomplete. */
        public List<Event> finish() {
            incomplete = line.size() > 0 || inEvent;
            line.reset();
            return events;
        }

        public boolean incomplete() {
            return incomplete;
        }

        private void line(String text) {
            if (first) {
                first = false;
                if (text.startsWith("﻿")) {
                    text = text.substring(1);
                }
            }
            if (text.isEmpty()) {
                if (hasData) {
                    String d = data.toString();
                    events.add(
                            new Event(type == null ? "message" : type, d.substring(0, d.length() - 1), lastId, retry));
                }
                inEvent = hasData = false;
                type = null;
                retry = null;
                data.setLength(0);
                return;
            }
            if (text.startsWith(":")) {
                return;
            }
            inEvent = true;
            int colon = text.indexOf(':');
            String name = colon < 0 ? text : text.substring(0, colon);
            String value = colon < 0 ? "" : text.substring(colon + 1);
            if (value.startsWith(" ")) {
                value = value.substring(1);
            }
            switch (name) {
                case "event" -> type = value;
                case "data" -> {
                    data.append(value).append('\n');
                    hasData = true;
                }
                case "id" -> {
                    if (value.indexOf('\0') < 0) {
                        lastId = value;
                    }
                }
                case "retry" -> {
                    if (!value.isEmpty() && value.chars().allMatch(Character::isDigit)) {
                        retry = Integer.valueOf(value);
                    }
                }
                default -> {} // ignored, as the standard says
            }
        }
    }
}
