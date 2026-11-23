package com.epam.aidial.provider.translation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The plumbing: flow control and the end, with no Vert.x running (the stream is driven by hand). */
class TranslatedStreamTest {

    private static final String CREATED = "data: {\"type\":\"response.created\",\"response\":{\"id\":\"r\"}}\n\n";
    private static final String DELTA = "data: {\"type\":\"response.output_text.delta\",\"output_index\":0,"
            + "\"content_index\":0,\"delta\":\"x\"}\n\n";
    private static final String COMPLETED = "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"r\"}}\n\n";

    private final TranslatedStream stream = new TranslatedStream(new StreamTranslator("m"));
    private final List<String> received = new ArrayList<>();
    private final AtomicBoolean ended = new AtomicBoolean();

    private ReadStream<Buffer> attach() {
        ReadStream<Buffer> output = stream.output();
        output.handler(chunk -> received.add(chunk.toString()));
        output.endHandler(v -> ended.set(true));
        return output;
    }

    private static String types(List<String> chunks) {
        return String.join("", chunks)
                .lines()
                .filter(line -> line.startsWith("event: "))
                .map(line -> line.substring(7))
                .toList()
                .toString();
    }

    @Test
    void translatesAndEndsWhenTheUpstreamEnds() {
        attach();
        stream.sink().write(Buffer.buffer(CREATED + DELTA + COMPLETED));
        assertFalse(ended.get(), "the sink has not ended");
        stream.sink().end();
        assertTrue(ended.get());
        assertEquals(
                "[message_start, content_block_start, content_block_delta, content_block_stop, message_delta,"
                        + " message_stop]",
                types(received));
    }

    @Test
    void holdsBackWhatNobodyReadsYet() {
        stream.sink().write(Buffer.buffer(CREATED));
        stream.sink().end();
        assertEquals(List.of(), received);
        attach();
        assertEquals("[message_start, error]", types(received));
        assertTrue(ended.get());
    }

    @Test
    void emitsOnlyWhatTheReaderAsksFor() {
        ReadStream<Buffer> output = attach();
        output.pause();
        stream.sink().write(Buffer.buffer(CREATED + DELTA));
        assertEquals(List.of(), received);
        output.fetch(1);
        assertEquals("[message_start]", types(received));
        output.fetch(2);
        assertEquals("[message_start, content_block_start, content_block_delta]", types(received));
        output.resume();
        stream.sink().write(Buffer.buffer(COMPLETED));
        stream.sink().end();
        assertTrue(ended.get());
    }

    @Test
    void theSinkIsFullWhileTheReaderLagsAndDrainsWhenItCatchesUp() {
        ReadStream<Buffer> output = attach();
        output.pause();
        AtomicInteger drained = new AtomicInteger();
        stream.sink().drainHandler(v -> drained.incrementAndGet());
        StringBuilder events = new StringBuilder(CREATED);
        for (int i = 0; i < TranslatedStream.HIGH_WATER_MARK; i++) {
            events.append(DELTA.replace("\"content_index\":0", "\"content_index\":" + i));
        }
        stream.sink().write(Buffer.buffer(events.toString()));
        assertTrue(stream.sink().writeQueueFull());
        assertEquals(0, drained.get());
        output.resume();
        assertFalse(stream.sink().writeQueueFull());
        assertEquals(1, drained.get());
    }

    @Test
    void failureOfTheUpstreamIsAnErrorEventThenAnEnd() {
        attach();
        stream.sink().write(Buffer.buffer(CREATED));
        stream.fail(new IllegalStateException("connection reset"));
        assertEquals("[message_start, error]", types(received));
        assertTrue(String.join("", received).contains("Internal server error"));
        assertFalse(String.join("", received).contains("connection reset"), "the detail is for the log");
        assertTrue(ended.get());
    }

    @Test
    void failureAfterTheEndChangesNothing() {
        attach();
        stream.sink().write(Buffer.buffer(CREATED + COMPLETED));
        stream.sink().end();
        int chunks = received.size();
        stream.fail(new IllegalStateException("late"));
        assertEquals(chunks, received.size());
    }

    @Test
    void nothingIsWrittenAfterTheEnd() {
        attach();
        stream.sink().end();
        stream.sink().write(Buffer.buffer(CREATED));
        assertEquals("[error]", types(received));
    }
}
