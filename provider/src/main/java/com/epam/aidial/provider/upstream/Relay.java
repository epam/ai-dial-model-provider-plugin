package com.epam.aidial.provider.upstream;

import com.epam.aidial.provider.api.AdapterResponse;
import com.epam.aidial.provider.api.Body;
import com.epam.aidial.provider.api.CacheBreakpoint;
import com.epam.aidial.provider.api.CacheHeaders;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import java.time.Clock;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;

public final class Relay {

    private Relay() {}

    /**
     * @param headers a copy, which the answer then owns
     * @param stream  null for a body read whole before anything is written, so that a stalled or cut body is an
     *     internal failure, not a cut 200
     */
    public record Success(
            UnaryOperator<MultiMap> headers, @Nullable Function<UpstreamClient.UpstreamResponse, Body.Stream> stream) {

        public static Success whole(UnaryOperator<MultiMap> headers) {
            return new Success(headers, null);
        }

        public static Success streamed(
                UnaryOperator<MultiMap> headers, Function<UpstreamClient.UpstreamResponse, Body.Stream> stream) {
            return new Success(headers, stream);
        }
    }

    /**
     * @param errorHeaders a copy
     * @param breakpoint null for none; stamped on a 200 only: after a failure DIAL Core tries another upstream, so no
     *     other status takes part in the cache affinity
     */
    public static Future<AdapterResponse> relay(
            UpstreamClient.UpstreamResponse response,
            UnaryOperator<MultiMap> errorHeaders,
            Success success,
            @Nullable CacheBreakpoint breakpoint,
            Clock clock) {
        if (!response.isSuccess()) {
            return response.body()
                    .map(body -> new AdapterResponse(
                            response.status(), errorHeaders.apply(response.headers()), new Body.Bytes(body)));
        }
        MultiMap headers = success.headers().apply(response.headers());
        if (success.stream() != null) {
            Body.Stream body = success.stream().apply(response);
            return Future.succeededFuture(
                    stamped(new AdapterResponse(response.status(), headers, body), breakpoint, clock));
        }
        return response.body()
                .map(body -> stamped(
                        new AdapterResponse(response.status(), headers, new Body.Bytes(body)), breakpoint, clock));
    }

    private static AdapterResponse stamped(AdapterResponse answer, @Nullable CacheBreakpoint breakpoint, Clock clock) {
        if (breakpoint == null || answer.status() != 200) {
            return answer;
        }
        CacheHeaders.stamp(answer.headers(), breakpoint, clock.instant());
        return answer;
    }
}
