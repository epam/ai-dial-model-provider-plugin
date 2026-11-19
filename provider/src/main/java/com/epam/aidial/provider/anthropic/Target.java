package com.epam.aidial.provider.anthropic;

import com.epam.aidial.provider.api.InvalidRequestException;
import com.epam.aidial.provider.credentials.Secret;
import com.google.common.net.PercentEscaper;
import java.util.List;
import org.jspecify.annotations.Nullable;

public sealed interface Target {

    List<String> PLATFORM_KEY_HEADERS = List.of("X-Api-Key");

    /** The Anthropic SDK's Foundry client sends its key as api-key on top of the base client's x-api-key. */
    List<String> FOUNDRY_KEY_HEADERS = List.of("Api-Key", "X-Api-Key");

    Cloud cloud();

    /**
     * Without a trailing "/". Vertex AI: up to .../publishers/anthropic/models; the others: up to /anthropic or the
     * host.
     */
    String baseUrl();

    String url(Endpoint endpoint, String model, boolean stream);

    /** Whether the cloud's SDK sends anthropic_version in the body, and no default anthropic-version header. */
    default boolean versionInBody() {
        return false;
    }

    /** Its streams are AWS event streams, not SSE. */
    default boolean invokeApi() {
        return false;
    }

    /** The invoke-model API has no count_tokens. */
    default boolean serves(Endpoint endpoint) {
        return true;
    }

    /**
     * @param key an empty one sends no key header
     */
    record Platform(String baseUrl, Secret key) implements Target {

        static Platform of(String baseUrl, String key) {
            return new Platform(baseUrl, new Secret(key));
        }

        @Override
        public Cloud cloud() {
            return Cloud.PLATFORM;
        }

        @Override
        public String url(Endpoint endpoint, String model, boolean stream) {
            return baseUrl + endpoint.path();
        }
    }

    /**
     * @param key null for none, where Foundry takes an Azure AD token. An empty key is a key.
     */
    record Foundry(String baseUrl, @Nullable Secret key) implements Target {

        static Foundry of(String baseUrl, @Nullable String key) {
            return new Foundry(baseUrl, Secret.of(key));
        }

        @Override
        public Cloud cloud() {
            return Cloud.AZURE;
        }

        @Override
        public String url(Endpoint endpoint, String model, boolean stream) {
            return baseUrl + endpoint.path();
        }
    }

    record Vertex(String baseUrl) implements Target {

        private static final PercentEscaper PATH_SEGMENT = new PercentEscaper("-_.~$&+:=@", false);

        @Override
        public Cloud cloud() {
            return Cloud.GCP;
        }

        @Override
        public boolean versionInBody() {
            return true;
        }

        @Override
        public String url(Endpoint endpoint, String model, boolean stream) {
            if (endpoint == Endpoint.COUNT_TOKENS) {
                return baseUrl + "/count-tokens:rawPredict";
            }
            // The model comes from the request body: escaped, it can only ever name a model.
            return baseUrl + "/" + pathEscape(model) + (stream ? ":streamRawPredict" : ":rawPredict");
        }

        static String pathEscape(String s) {
            return escapeModel(PATH_SEGMENT, s);
        }
    }

    record Bedrock(BedrockClient client, String baseUrl, String region, BedrockCredentials credentials)
            implements Target {

        private static final PercentEscaper MODEL_ID = new PercentEscaper("_.-~:", false);

        @Override
        public Cloud cloud() {
            return Cloud.AWS;
        }

        @Override
        public boolean invokeApi() {
            return client == BedrockClient.LEGACY;
        }

        @Override
        public boolean versionInBody() {
            return invokeApi();
        }

        @Override
        public boolean serves(Endpoint endpoint) {
            return !invokeApi() || endpoint == Endpoint.MESSAGES;
        }

        String service() {
            return client.service();
        }

        /** The Anthropic SDK's Bedrock client signs for us-east-1 when it has no region. */
        String signingRegion() {
            return invokeApi() && region.isEmpty() ? "us-east-1" : region;
        }

        @Override
        public String url(Endpoint endpoint, String model, boolean stream) {
            if (invokeApi()) {
                return baseUrl + "/model/" + quoteModel(model) + (stream ? "/invoke-with-response-stream" : "/invoke");
            }
            return baseUrl + endpoint.path();
        }

        static String quoteModel(String model) {
            return escapeModel(MODEL_ID, model);
        }
    }

    /** A text no URL can carry (an unpaired surrogate) is the client's to fix. */
    private static String escapeModel(PercentEscaper escaper, String model) {
        try {
            return escaper.escape(model);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("model: Input should be valid Unicode text");
        }
    }
}
