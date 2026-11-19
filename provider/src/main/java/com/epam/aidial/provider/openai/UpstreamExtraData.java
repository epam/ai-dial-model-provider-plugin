package com.epam.aidial.provider.openai;

import com.epam.aidial.provider.api.AdapterRequest;
import com.epam.aidial.provider.credentials.AwsExtraData;
import com.epam.aidial.provider.json.Json;
import com.epam.aidial.provider.upstream.UpstreamRefusedException;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectReader;
import io.vertx.core.MultiMap;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Unknown members are ignored, a repeated one keeps its last value. A refused extra data is a 502. */
record UpstreamExtraData(List<String> headersToProxy, @Nullable Vendor vendor, AwsExtraData aws) {

    enum Vendor {
        @JsonProperty("alibaba-cloud")
        ALIBABA_CLOUD
    }

    /** Every member is optional; a scalar is no string. */
    record Wire(@JsonProperty("headers_to_proxy") @Nullable List<String> headersToProxy, @Nullable Vendor vendor) {}

    private static final ObjectReader WIRE = Json.binder(Wire.class);
    private static final ObjectReader AWS = Json.binder(AwsExtraData.class);

    static final UpstreamExtraData NONE = new UpstreamExtraData(List.of(), null, AwsExtraData.NONE);

    /** NONE when absent; throws {@link UpstreamRefusedException} (502) when refused. */
    static UpstreamExtraData of(MultiMap headers) {
        String raw = headers.get(AdapterRequest.EXTRA_DATA_HEADER);
        return raw == null ? NONE : parse(raw);
    }

    /** A proxied Accept-Encoding is not sent: the answer is relayed, not decoded. */
    MultiMap proxied(MultiMap headers) {
        MultiMap out = MultiMap.caseInsensitiveMultiMap();
        for (String name : headersToProxy) {
            String v = headers.get(name);
            if (v != null && !name.equalsIgnoreCase("Accept-Encoding")) {
                out.set(name, v);
            }
        }
        return out;
    }

    private static UpstreamExtraData parse(String raw) {
        Wire wire;
        AwsExtraData aws;
        try {
            wire = WIRE.readValue(raw);
            aws = AWS.readValue(raw);
        } catch (JsonParseException e) {
            throw invalid("doesn't contain valid JSON");
        } catch (JsonProcessingException e) {
            throw invalid("doesn't contain the expected JSON object");
        }
        if (wire == null || aws == null) {
            throw invalid("doesn't contain the expected JSON object"); // a JSON null
        }
        return new UpstreamExtraData(
                wire.headersToProxy() == null ? List.of() : wire.headersToProxy(), wire.vendor(), aws);
    }

    /** No detail of the value goes into the message: it may hold AWS credentials. */
    private static UpstreamRefusedException invalid(String problem) {
        return UpstreamRefusedException.badGateway(AdapterRequest.EXTRA_DATA_HEADER + " header " + problem);
    }
}
