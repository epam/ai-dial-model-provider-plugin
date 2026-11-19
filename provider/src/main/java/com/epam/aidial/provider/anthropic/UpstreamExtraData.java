package com.epam.aidial.provider.anthropic;

import com.epam.aidial.provider.api.AdapterRequest;
import com.epam.aidial.provider.credentials.AwsExtraData;
import com.epam.aidial.provider.json.Json;
import com.epam.aidial.provider.upstream.UpstreamRefusedException;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * An absent or empty header gives no members; a header that is not a JSON object, or has a member of another type than
 * the upstream reads, is an {@link UpstreamRefusedException}.
 */
final class UpstreamExtraData {

    private UpstreamExtraData() {}

    /** An empty string or a JSON null is as absent. */
    static final class Vertex {

        @JsonProperty("region")
        @Nullable
        String region;

        @JsonProperty("project")
        @Nullable
        String project;

        static Vertex of(AdapterRequest request) {
            return read(request, Vertex.class, Vertex::new);
        }

        String regionOr(String defaultValue) {
            return region == null || region.isEmpty() ? defaultValue : region;
        }

        String projectOr(String defaultValue) {
            return project == null || project.isEmpty() ? defaultValue : project;
        }
    }

    /** A JSON null for the region or the Claude client is rejected. */
    record Bedrock(@Nullable String region, @Nullable String claudeClient, AwsExtraData aws) {

        /** Bound by fields, so that an absent member is no null. */
        static final class Wire {

            @JsonProperty("region")
            @JsonSetter(nulls = Nulls.FAIL)
            @Nullable
            String region;

            @JsonProperty("claude_client")
            @JsonSetter(nulls = Nulls.FAIL)
            @Nullable
            String claudeClient;
        }

        static Bedrock of(AdapterRequest request) {
            Wire wire = read(request, Wire.class, Wire::new);
            return new Bedrock(
                    wire.region, wire.claudeClient, read(request, AwsExtraData.class, () -> AwsExtraData.NONE));
        }
    }

    private static <T> T read(AdapterRequest request, Class<T> type, Supplier<T> none) {
        String raw = request.headers().get(AdapterRequest.EXTRA_DATA_HEADER);
        if (raw == null || raw.isEmpty()) {
            return none.get();
        }
        try {
            T bound = Json.binder(type).readValue(raw);
            if (bound != null) {
                return bound;
            }
        } catch (JsonProcessingException e) {
            // answered below, with no word of the value: it may hold credentials
        }
        throw UpstreamRefusedException.misconfigured(
                AdapterRequest.EXTRA_DATA_HEADER + " is not a JSON object with the members this upstream reads");
    }
}
