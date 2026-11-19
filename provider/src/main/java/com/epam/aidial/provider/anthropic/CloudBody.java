package com.epam.aidial.provider.anthropic;

import com.epam.aidial.provider.json.BodyFields;
import com.epam.aidial.provider.json.Json;
import java.util.List;
import org.jspecify.annotations.Nullable;

final class CloudBody {

    // JSON strings: the SDKs' default anthropic_version of each cloud
    private static final String VERTEX_VERSION = "\"vertex-2023-10-16\"";
    private static final String BEDROCK_VERSION = "\"bedrock-2023-05-31\"";

    private CloudBody() {}

    /**
     * @param betas the anthropic-beta header as it goes upstream; Bedrock invoke-model takes its flags in the body
     */
    static boolean adapt(BodyFields fields, Target target, Endpoint endpoint, @Nullable String betas) {
        return switch (target) {
            case Target.Vertex ignored -> adaptToVertex(fields, endpoint);
            case Target.Bedrock aws -> adaptToBedrock(fields, aws, betas);
            case Target.Foundry ignored -> false;
            case Target.Platform ignored -> false;
        };
    }

    private static boolean adaptToVertex(BodyFields fields, Endpoint endpoint) {
        boolean changed = false;
        if (endpoint == Endpoint.MESSAGES) {
            fields.delete("model"); // count_tokens keeps it
            changed = true;
        }
        if (!fields.has("anthropic_version")) {
            fields.set("anthropic_version", VERTEX_VERSION);
            changed = true;
        }
        return changed;
    }

    private static boolean adaptToBedrock(BodyFields fields, Target.Bedrock aws, @Nullable String betas) {
        if (!aws.invokeApi()) {
            return false;
        }
        fields.delete("model");
        fields.delete("stream");
        if (!fields.has("anthropic_version")) {
            fields.set("anthropic_version", BEDROCK_VERSION);
        }
        if (betas != null && !betas.isEmpty() && !fields.has("anthropic_beta")) {
            fields.set("anthropic_beta", Json.encode(List.of(betas.split(",", -1))));
        }
        return true;
    }
}
