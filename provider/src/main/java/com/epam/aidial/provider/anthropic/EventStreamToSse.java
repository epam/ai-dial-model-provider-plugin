package com.epam.aidial.provider.anthropic;

import com.epam.aidial.provider.anthropic.EventStreamFrames.Malformed;
import com.epam.aidial.provider.json.Json;
import com.epam.aidial.provider.upstream.StreamHandlers;
import com.epam.aidial.provider.upstream.UpstreamClient;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Any failure (a frame that does not decode, an exception message, a cut stream) fails the stream, so the client sees
 * it truncated and never a clean end. Created paused, as the upstream body is; flow control is the upstream body's.
 */
final class EventStreamToSse implements ReadStream<Buffer> {

    private static final Logger log = LoggerFactory.getLogger(EventStreamToSse.class);
    private static final byte[] BYTES_PREFIX = "{\"bytes\":\"".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] TYPE_PREFIX = "{\"type\":\"".getBytes(StandardCharsets.US_ASCII);

    private final UpstreamClient.UpstreamResponse upstream;
    private final ReadStream<Buffer> source;
    private final Future<Void> aborted;
    private final EventStreamFrames frames = new EventStreamFrames();
    private final StreamHandlers out = new StreamHandlers();
    private boolean failed;

    EventStreamToSse(UpstreamClient.UpstreamResponse upstream, Future<Void> aborted) {
        this.upstream = upstream;
        this.source = upstream.stream();
        this.aborted = aborted;
        source.handler(this::onData);
        source.endHandler(v -> {
            if (frames.partial()) {
                fail(new Malformed("event stream: unexpected EOF"));
            } else if (!failed) {
                out.end();
            }
        });
        source.exceptionHandler(this::onFailure);
    }

    private void onData(Buffer data) {
        if (failed) {
            return;
        }
        Buffer events = Buffer.buffer();
        try {
            frames.feed(data, m -> appendChunk(m, events));
        } catch (Malformed e) {
            // each event decoded before the failure is sent first
            emit(events);
            fail(e);
            return;
        }
        emit(events);
    }

    private void emit(Buffer events) {
        if (events.length() > 0) {
            out.emit(events);
        }
    }

    /** Abandons the upstream body: its connection is reset and its slot released. */
    private void fail(Throwable e) {
        upstream.discard();
        onFailure(e);
    }

    private void onFailure(Throwable e) {
        if (failed) {
            return;
        }
        failed = true;
        if (!aborted.isComplete()) {
            log.atWarn()
                    .addKeyValue("error", e.getMessage() == null ? e.toString() : e.getMessage())
                    .log("Bedrock event stream failed");
        }
        out.fail(e);
    }

    private static void appendChunk(EventStreamFrames.Message m, Buffer out) {
        byte[] payload = m.payload();
        if (!"event".equals(m.headers().get(":message-type"))) {
            throw new Malformed("upstream exception: " + new String(payload, StandardCharsets.UTF_8));
        }
        if ("chunk".equals(m.headers().get(":event-type"))) {
            appendSse(out, chunkBytes(payload));
        }
    }

    private static byte[] chunkBytes(byte[] payload) {
        String bytes = stringMember(payload, BYTES_PREFIX, "bytes");
        if (bytes == null) {
            throw new Malformed("a chunk that is not a JSON object with a bytes string");
        }
        try {
            return Base64.getDecoder().decode(bytes);
        } catch (IllegalArgumentException e) {
            throw new Malformed(e.getMessage(), e);
        }
    }

    private static void appendSse(Buffer out, byte[] data) {
        out.appendString("event: ").appendString(eventType(data)).appendString("\n");
        int start = 0;
        for (int i = 0; i <= data.length; i++) {
            if (i == data.length || data[i] == '\n') {
                out.appendString("data: ").appendBytes(data, start, i - start).appendString("\n");
                start = i + 1;
            }
        }
        out.appendString("\n");
    }

    static String eventType(byte[] data) {
        String type = stringMember(data, TYPE_PREFIX, "type");
        return type == null || type.isEmpty() ? "completion" : type;
    }

    /**
     * Null when the data is not a JSON object or has no such string. The shape the upstream sends (the member first,
     * nothing to unescape; {@code prefix} is its opening) is read without a JSON parser; anything else takes the
     * decoder.
     */
    private static String stringMember(byte[] json, byte[] prefix, String name) {
        if (json.length >= prefix.length && Arrays.equals(json, 0, prefix.length, prefix, 0, prefix.length)) {
            for (int i = prefix.length; i < json.length; i++) {
                if (json[i] == '"') {
                    return new String(json, prefix.length, i - prefix.length, StandardCharsets.UTF_8);
                }
                if (json[i] == '\\') {
                    break;
                }
            }
        }
        return Json.parseOrNull(json) instanceof Map<?, ?> object && object.get(name) instanceof String value
                ? value
                : null;
    }

    @Override
    public EventStreamToSse handler(Handler<Buffer> handler) {
        out.handler(handler);
        return this;
    }

    @Override
    public EventStreamToSse exceptionHandler(Handler<Throwable> handler) {
        out.exceptionHandler(handler);
        return this;
    }

    @Override
    public EventStreamToSse endHandler(Handler<Void> handler) {
        out.endHandler(handler);
        return this;
    }

    @Override
    public EventStreamToSse pause() {
        source.pause();
        return this;
    }

    @Override
    public EventStreamToSse resume() {
        source.resume();
        return this;
    }

    @Override
    public EventStreamToSse fetch(long amount) {
        source.fetch(amount);
        return this;
    }
}
