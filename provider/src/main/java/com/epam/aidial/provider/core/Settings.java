package com.epam.aidial.provider.core;

import com.epam.aidial.provider.api.ConfigException;
import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.type.LogicalType;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Every setting of the service. A setting that is not set is {@code null}, and so is one set to JSON {@code null}; the
 * defaults are {@link Config}'s. A name that is no setting is refused by {@link #of}, and ignored by {@link #from}, as
 * the process environment holds names of its own.
 *
 * <p>The first setting that is refused, in the order below, is reported by a {@link ConfigException} that names the
 * setting and not its value, which may be a credential.
 */
@JsonClassDescription("The settings of a model provider.")
public record Settings(
        @JsonProperty(value = ADAPTER, required = true)
                @JsonPropertyDescription(
                        "The cloud API the entry serves: openai (Azure OpenAI and OpenAI), vertexai (Anthropic on Vertex AI) or bedrock (Anthropic on Amazon Bedrock). Not a setting of the process environment.")
                Adapter adapter,
        @JsonProperty("HTTP_MAX_CONNECTIONS")
                @JsonPropertyDescription(
                        "Upstream calls in flight per adapter. Adapters: openai, vertexai. Default: 1000 for openai, 250 for vertexai.")
                @Nullable
                Integer httpMaxConnections,
        @JsonProperty("HTTP_POOL_TIMEOUT")
                @JsonPropertyDescription(
                        "Seconds a call waits for a free slot before it fails with 503; 0 waits without limit. Adapters: openai, vertexai. Default: 10.")
                @Nullable
                Integer httpPoolTimeout,
        @JsonProperty("ANTHROPIC_MAX_CONNECTIONS")
                @JsonPropertyDescription("Upstream calls in flight. Adapter: bedrock. Default: 1000.")
                @Nullable
                Integer anthropicMaxConnections,
        @JsonProperty("ANTHROPIC_POOL_TIMEOUT")
                @JsonPropertyDescription(
                        "Seconds a call waits for a free slot before it fails with 503; 0 or less fails at once. Adapter: bedrock. Default: 10.")
                @Nullable
                Integer anthropicPoolTimeout,
        @JsonProperty("REQUEST_TIMEOUT_SECONDS")
                @JsonPropertyDescription(
                        "Seconds to wait for response headers and for each body read. Adapter: bedrock. Default: 600.")
                @Nullable
                Integer requestTimeoutSeconds,
        @JsonProperty("SSE_HEARTBEAT_INTERVAL")
                @JsonPropertyDescription(
                        "Seconds of silence in a Responses stream before a ': ping' comment; 0 turns it off. Adapter: openai. Default: 0.")
                @Nullable
                Integer sseHeartbeatInterval,
        @JsonProperty(TAGS)
                @JsonPropertyDescription(
                        "Tags of the session of an assumed role: an object of tag keys to Bedrock.modelId or UserInfo.<path>. In the process environment, the JSON of the object. Adapter: bedrock.")
                @JsonSetter(contentNulls = Nulls.FAIL)
                @Nullable
                Map<String, String> awsSessionTags,
        @JsonProperty("HTTP_PROXY")
                @JsonPropertyDescription("Proxy URL for upstream calls over HTTP. All adapters.")
                @Nullable
                String httpProxy,
        @JsonProperty("HTTPS_PROXY")
                @JsonPropertyDescription("Proxy URL for upstream calls over HTTPS. All adapters.")
                @Nullable
                String httpsProxy,
        @JsonProperty("NO_PROXY")
                @JsonPropertyDescription(
                        "Comma-separated hosts that upstream calls reach without the proxy. All adapters.")
                @Nullable
                String noProxy,
        @JsonProperty("DIAL_URL")
                @JsonPropertyDescription("DIAL Core URL, for the user info of the AWS session tags. All adapters.")
                @Nullable
                String dialUrl,
        @JsonProperty("AZURE_OPEN_AI_SCOPE")
                @JsonPropertyDescription(
                        "Scope of the Azure AD token. Adapter: openai. Default: https://cognitiveservices.azure.com/.default.")
                @Nullable
                String azureOpenAiScope,
        @JsonProperty("AWS_BEDROCK_BASE_URL")
                @JsonPropertyDescription("Base URL of Bedrock Mantle, for the Responses API. Adapter: openai.")
                @Nullable
                String awsBedrockBaseUrl,
        @JsonProperty("AWS_BEARER_TOKEN_BEDROCK")
                @JsonPropertyDescription("Bearer token of Bedrock Mantle, for the Responses API. Adapter: openai.")
                @Nullable
                String awsBearerTokenBedrock,
        @JsonProperty("VLLM_DEPLOYMENTS")
                @JsonPropertyDescription(
                        "Comma-separated models that need an X-Upstream-Key, else the call fails with 401. Adapter: openai.")
                @Nullable
                String vllmDeployments,
        @JsonProperty("QWEN3_ASR_VLLM_DEPLOYMENTS")
                @JsonPropertyDescription(
                        "Comma-separated models that need an X-Upstream-Key, else the call fails with 401. Adapter: openai.")
                @Nullable
                String qwen3AsrVllmDeployments,
        @JsonProperty("ANTHROPIC_BASE_URL")
                @JsonPropertyDescription(
                        "Anthropic API used with a key. Adapters: vertexai, bedrock. Default: https://api.anthropic.com.")
                @Nullable
                String anthropicBaseUrl,
        @JsonProperty("GCP_DEFAULT_REGION")
                @JsonPropertyDescription("Region when the model's extraData has none. Adapter: vertexai.")
                @Nullable
                String gcpDefaultRegion,
        @JsonProperty("GCP_PROJECT_ID")
                @JsonPropertyDescription("Project when the model's extraData has none. Adapter: vertexai.")
                @Nullable
                String gcpProjectId,
        @JsonProperty("ANTHROPIC_VERTEX_BASE_URL")
                @JsonPropertyDescription("Vertex AI base URL. Adapter: vertexai. Default: per region.")
                @Nullable
                String anthropicVertexBaseUrl,
        @JsonProperty("GOOGLE_APPLICATION_CREDENTIALS")
                @JsonPropertyDescription(
                        "Path of the application default credentials, or of an AWS workload identity file. Adapter: vertexai.")
                @Nullable
                String googleApplicationCredentials,
        @JsonProperty("GOOGLE_CLOUD_QUOTA_PROJECT")
                @JsonPropertyDescription("Quota project of the credentials. Adapter: vertexai.")
                @Nullable
                String googleCloudQuotaProject,
        @JsonProperty("AWS_DEFAULT_REGION")
                @JsonPropertyDescription("Region when the model's extraData has none. Adapter: bedrock.")
                @Nullable
                String awsDefaultRegion,
        @JsonProperty("AWS_CLAUDE_DEFAULT_CLIENT")
                @JsonPropertyDescription(
                        "How Claude is called: legacy (invoke-model) or mantle. Adapter: bedrock. Default: legacy.")
                @Nullable
                String awsClaudeDefaultClient,
        @JsonProperty("ANTHROPIC_BEDROCK_BASE_URL")
                @JsonPropertyDescription("Base URL of invoke-model. Adapter: bedrock. Default: per region.")
                @Nullable
                String anthropicBedrockBaseUrl,
        @JsonProperty("ANTHROPIC_BEDROCK_MANTLE_BASE_URL")
                @JsonPropertyDescription("Base URL of Mantle. Adapter: bedrock. Default: per region.")
                @Nullable
                String anthropicBedrockMantleBaseUrl,
        @JsonProperty("AWS_ACCESS_KEY_ID")
                @JsonPropertyDescription(
                        "AWS credentials when the model's extraData has none, else the SDK's default chain. Adapter: bedrock. Keep credentials in DIAL Core's environment.")
                @Nullable
                String awsAccessKeyId,
        @JsonProperty("AWS_SECRET_ACCESS_KEY")
                @JsonPropertyDescription(
                        "AWS credentials when the model's extraData has none, else the SDK's default chain. Adapter: bedrock. Keep credentials in DIAL Core's environment.")
                @Nullable
                String awsSecretAccessKey,
        @JsonProperty("AWS_SESSION_TOKEN")
                @JsonPropertyDescription(
                        "AWS credentials when the model's extraData has none, else the SDK's default chain. Adapter: bedrock. Keep credentials in DIAL Core's environment.")
                @Nullable
                String awsSessionToken,
        @JsonProperty("AWS_ASSUME_ROLE_ARN")
                @JsonPropertyDescription(
                        "Role to assume with the AWS credentials when the model's extraData has none. Adapter: bedrock.")
                @Nullable
                String awsAssumeRoleArn,
        @JsonProperty("AWS_ENDPOINT_URL_STS")
                @JsonPropertyDescription("STS endpoint for assuming roles. Adapter: bedrock.")
                @Nullable
                String awsEndpointUrlSts,
        @JsonProperty("AWS_ENDPOINT_URL")
                @JsonPropertyDescription(
                        "STS endpoint for assuming roles, when AWS_ENDPOINT_URL_STS is not set. Adapter: bedrock.")
                @Nullable
                String awsEndpointUrl,
        @JsonProperty("http_proxy")
                @JsonPropertyDescription(
                        "Lower-case form of HTTP_PROXY, used when it is not set or empty. All adapters.")
                @Nullable
                String httpProxyLowerCase,
        @JsonProperty("https_proxy")
                @JsonPropertyDescription(
                        "Lower-case form of HTTPS_PROXY, used when it is not set or empty. All adapters.")
                @Nullable
                String httpsProxyLowerCase,
        @JsonProperty("no_proxy")
                @JsonPropertyDescription("Lower-case form of NO_PROXY, used when it is not set or empty. All adapters.")
                @Nullable
                String noProxyLowerCase,
        @JsonProperty("DEFAULT_REGION")
                @JsonPropertyDescription(
                        "Deprecated: the region when GCP_DEFAULT_REGION is not set or empty. Adapter: vertexai.")
                @Nullable
                String defaultRegion,
        @JsonProperty("AWS_REGION")
                @JsonPropertyDescription(
                        "Region of the AWS calls, then AWS_DEFAULT_REGION, then us-east-1. Adapter: bedrock.")
                @Nullable
                String awsRegion,
        @JsonProperty("AWS_PROFILE")
                @JsonPropertyDescription(
                        "Profile of the AWS shared config, when the model's extraData has no credentials. Adapter: bedrock.")
                @Nullable
                String awsProfile) {

    public static final String ADAPTER = "adapter";

    private static final String TAGS = "AWS_SESSION_TAGS";

    /**
     * Lax, as the schema is the strict one: text is an integer when it reads as one, and a number is the text of a
     * string. Not an empty text as an integer, and not a number as an adapter: its place in the enum is not its name.
     */
    private static final ObjectMapper MAPPER = mapper();

    private static ObjectMapper mapper() {
        ObjectMapper mapper = JsonMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .build();
        mapper.coercionConfigFor(LogicalType.Integer).setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail);
        return mapper;
    }

    /** A negative interval turns the heartbeat off, as 0 does. */
    public Settings {
        if (sseHeartbeatInterval != null && sseHeartbeatInterval < 0) {
            sseHeartbeatInterval = 0;
        }
    }

    /**
     * The settings of a process environment, which serves every adapter: {@link #adapter()} is the first one, and
     * nothing reads it.
     *
     * @throws ConfigException for the first setting that is refused
     */
    public static Settings from(Map<String, String> environment) {
        return of(MAPPER.createObjectNode().put(ADAPTER, Adapter.values()[0].value()), environment);
    }

    /**
     * The settings of an entry, and for the ones it leaves out, those of an environment.
     *
     * @throws ConfigException for the first setting that is refused
     */
    public static Settings of(ObjectNode entry, Map<String, String> environment) {
        try {
            return MAPPER.treeToValue(mergeWithEnvironment(entry, environment), Settings.class);
        } catch (JsonProcessingException e) {
            throw invalid(e);
        }
    }

    /** The environment's settings but the adapter, under the entry's. */
    private static ObjectNode mergeWithEnvironment(ObjectNode entry, Map<String, String> environment) {
        ObjectNode merged = MAPPER.createObjectNode();
        Arrays.stream(Settings.class.getRecordComponents())
                .map(Settings::name)
                .filter(name -> !name.equals(ADAPTER) && environment.containsKey(name))
                .forEach(name -> merged.set(name, environment(name, environment.get(name))));
        entry.properties().stream()
                .filter(property -> !property.getValue().isNull())
                .forEach(property -> merged.set(property.getKey(), property.getValue()));
        return merged;
    }

    /** The session tags are the JSON of an object in the environment. */
    private static JsonNode environment(String name, String text) {
        if (name.equals(TAGS)) {
            try {
                JsonNode tags = MAPPER.readTree(text);
                if (!tags.isMissingNode()) {
                    return tags;
                }
            } catch (JsonProcessingException e) {
                // Stays text, which Jackson refuses where it reads the setting
            }
        }
        return MAPPER.getNodeFactory().textNode(text);
    }

    /** Without the cause: the message of Jackson holds the value, which may be a credential. */
    private static ConfigException invalid(JsonProcessingException e) {
        if (e instanceof JsonMappingException mapping) {
            for (Throwable cause = mapping; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConfigException config) {
                    return config;
                }
            }
            if (!mapping.getPath().isEmpty()) {
                String name = mapping.getPath().getFirst().getFieldName();
                return new ConfigException(name, name + " is invalid");
            }
        }
        return new ConfigException("", "the settings are invalid");
    }

    private static String name(RecordComponent component) {
        return component.getAccessor().getAnnotation(JsonProperty.class).value();
    }

    /** Prints no setting: one may be a credential. */
    @Override
    public String toString() {
        return "Settings[***]";
    }
}
