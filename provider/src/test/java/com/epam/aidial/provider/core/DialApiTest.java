package com.epam.aidial.provider.core;

import static com.epam.aidial.provider.testing.MockUpstream.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.epam.aidial.provider.testing.MockUpstream;
import com.epam.aidial.provider.upstream.ClientGoneException;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DialApiTest {

    private static final Future<Void> NEVER = Promise.<Void>promise().future();

    private Vertx vertx;
    private MockUpstream dial;
    private final AtomicInteger calls = new AtomicInteger();
    private volatile int resets;

    @BeforeEach
    void start() throws Exception {
        vertx = Vertx.vertx();
        dial = new MockUpstream(vertx).on("/", r -> {
            if (calls.incrementAndGet() <= resets) {
                r.connection().close();
            } else {
                r.response().end("{\"roles\":[]}");
            }
        });
    }

    @AfterEach
    void stop() throws Exception {
        await(vertx.close());
    }

    @Test
    void userInfoUrlAndHeaders() throws Exception {
        for (Map.Entry<String, String> c : Map.of(
                        "",
                        "/v1/user/info",
                        "/",
                        "/v1/user/info",
                        "//",
                        "//v1/user/info",
                        "/base",
                        "/base/v1/user/info")
                .entrySet()) {
            DialApi api = DialApi.from(vertx, Config.from(Map.of("DIAL_URL", dial.url() + c.getKey())));
            assertEquals(
                    "{\"roles\":[]}",
                    await(api.get(api.userInfoUrl(), "k1", DialApi.Retry.TRANSPORT, NEVER))
                            .toString());
            MockUpstream.Seen seen = dial.last();
            assertEquals(c.getValue(), seen.path(), c.getKey());
            assertEquals("k1", seen.headers().get("Api-Key"));
        }
        assertNull(DialApi.from(vertx, Config.from(Map.of())), "no DIAL_URL: nothing fetched from DIAL");
    }

    @Test
    void transportFailuresRetriedThreeTimes() throws Exception {
        DialApi api = DialApi.from(vertx, Config.from(Map.of("DIAL_URL", dial.url())));
        resets = 2;
        assertEquals(
                "{\"roles\":[]}",
                await(api.get(api.userInfoUrl(), "k1", DialApi.Retry.TRANSPORT, NEVER))
                        .toString());
        assertEquals(3, calls.get());

        calls.set(0);
        resets = 3;
        DialApi.DownloadException e = assertInstanceOf(
                DialApi.DownloadException.class,
                failure(api.get(api.userInfoUrl(), "k1", DialApi.Retry.TRANSPORT, NEVER)));
        assertNotNull(e.getCause(), "the transport failure that ended the attempts");
        assertEquals(3, calls.get());
    }

    @Test
    void statusesRetriedOnlyWhenAsked() throws Exception {
        DialApi api = DialApi.from(vertx, Config.from(Map.of("DIAL_URL", dial.url())));
        for (int status : List.of(500, 429, 408, 302)) {
            dial.reply("/v1/", status, Map.of("Location", "/elsewhere"), "");
            int before = dial.seen().size();
            assertEquals(
                    "DIAL answered " + status,
                    failure(api.get(api.userInfoUrl(), "k1", DialApi.Retry.TRANSPORT, NEVER))
                            .getMessage());
            assertEquals(before + 1, dial.seen().size(), status + " is not retried; a redirect is not followed");
        }
        dial.reply("/v1/", 429, Map.of(), "");
        int before = dial.seen().size();
        assertEquals(
                "DIAL answered 429",
                failure(api.get(api.userInfoUrl(), "k1", DialApi.Retry.TRANSPORT_AND_STATUS, NEVER))
                        .getMessage());
        assertEquals(before + 3, dial.seen().size());
    }

    @Test
    void clientGoneStopsTheRetries() throws Exception {
        DialApi api = DialApi.from(vertx, Config.from(Map.of("DIAL_URL", dial.url())));
        Promise<Void> aborted = Promise.promise();
        resets = 3;
        dial.on("/", r -> {
            calls.incrementAndGet();
            aborted.tryComplete();
            r.connection().close();
        });
        assertInstanceOf(
                ClientGoneException.class,
                failure(api.get(api.userInfoUrl(), "k1", DialApi.Retry.TRANSPORT, aborted.future())));
        assertEquals(1, calls.get());
    }

    private static Throwable failure(Future<?> future) {
        return assertThrows(ExecutionException.class, () -> await(future)).getCause();
    }
}
