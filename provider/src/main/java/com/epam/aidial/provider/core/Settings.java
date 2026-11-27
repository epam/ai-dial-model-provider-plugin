package com.epam.aidial.provider.core;

import com.epam.aidial.provider.api.ConfigException;
import com.epam.aidial.provider.json.Json;
import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.type.LogicalType;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * Every setting the service reads, each with the one type it is read as: an integer, a string, or for the session tags
 * an object of strings. A setting that is not set is {@code null}, and so is one set to JSON {@code null}; the defaults
 * are {@link Config}'s. There is no other setting: a name that is not one of these is refused by {@link #of}, and
 * ignored by {@link #from}, as the process environment holds names of its own.
 *
 * <p>An entry of DIAL Core's configuration gives them as JSON ({@link #of}); the process environment gives them as
 * text ({@link #from}). Either way, the first setting that is refused, in the order below, is reported by a
 * {@link ConfigException} that names the setting, not its value: it may be a credential.
 */
@JsonClassDescription("The settings of a model provider.")
public record Settings(
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
        @JsonProperty("AWS_SESSION_TAGS")
                @JsonPropertyDescription(
                        "Tags of the session of an assumed role: an object of tag keys to Bedrock.modelId or UserInfo.<path>. In the process environment, its JSON. Adapter: bedrock.")
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

    private static final String HEARTBEAT = "SSE_HEARTBEAT_INTERVAL";
    private static final String TAGS = "AWS_SESSION_TAGS";

    /** No coercion: a setting of another type than its own is refused, not converted. */
    private static final ObjectMapper MAPPER = mapper();

    private static ObjectMapper mapper() {
        ObjectMapper mapper = JsonMapper.builder()
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .build();
        mapper.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        return mapper;
    }

    private static final List<RecordComponent> COMPONENTS = Arrays.asList(Settings.class.getRecordComponents());
    private static final Set<String> NAMES =
            COMPONENTS.stream().map(Settings::name).collect(Collectors.toUnmodifiableSet());

    public Settings {
        if (sseHeartbeatInterval != null && sseHeartbeatInterval < 0) {
            throw new ConfigException(HEARTBEAT, mustBe(HEARTBEAT, Integer.class));
        }
        if (awsSessionTags != null && awsSessionTags.containsValue(null)) {
            throw new ConfigException(TAGS, mustBe(TAGS, Map.class));
        }
    }

    /**
     * The settings of a process environment: the names of the settings, as text. Other names are ignored.
     *
     * @throws ConfigException for the first setting that is refused
     */
    public static Settings from(Map<String, String> environment) {
        return of(MAPPER.createObjectNode(), environment);
    }

    /**
     * The settings of an entry, and for the ones it leaves out, or sets to {@code null}, those of an environment.
     *
     * @throws ConfigException for a name that is no setting, and for the first setting that is refused
     */
    public static Settings of(ObjectNode entry, Map<String, String> environment) {
        for (String name : (Iterable<String>) entry::fieldNames) {
            if (!NAMES.contains(name)) {
                throw new ConfigException(name, name + " is not a setting");
            }
        }
        ObjectNode merged = MAPPER.createObjectNode();
        for (RecordComponent component : COMPONENTS) {
            String name = name(component);
            JsonNode value = entry.get(name);
            if (value != null && !value.isNull()) {
                merged.set(name, value);
            } else if (environment.get(name) != null) {
                merged.set(name, text(name, component.getType(), environment.get(name)));
            }
        }
        try {
            return MAPPER.treeToValue(merged, Settings.class);
        } catch (JsonProcessingException e) {
            Throwable cause = e;
            while (cause != null) {
                if (cause instanceof ConfigException config) {
                    throw config;
                }
                cause = cause.getCause();
            }
            String name = e instanceof JsonMappingException mapping
                            && !mapping.getPath().isEmpty()
                    ? mapping.getPath().getFirst().getFieldName()
                    : "";
            // Without the cause: the message of Jackson holds the value
            throw new ConfigException(name, name + " must be " + kind(name, typeOf(name)));
        }
    }

    /** A setting of the environment as the JSON of its type. */
    private static JsonNode text(String name, Class<?> type, String text) {
        if (type == Integer.class) {
            try {
                return MAPPER.getNodeFactory().numberNode(Integer.parseInt(text));
            } catch (NumberFormatException e) {
                throw new ConfigException(name, mustBe(name, type), e);
            }
        }
        if (type == Map.class) {
            try {
                return MAPPER.valueToTree(Json.parseStringMap(text));
            } catch (Json.ParseException e) {
                throw new ConfigException(name, mustBe(name, type), e);
            }
        }
        return MAPPER.getNodeFactory().textNode(text);
    }

    private static Class<?> typeOf(String name) {
        return COMPONENTS.stream()
                .filter(component -> name(component).equals(name))
                .findFirst()
                .map(RecordComponent::getType)
                .orElse(String.class);
    }

    private static String name(RecordComponent component) {
        return component.getAccessor().getAnnotation(JsonProperty.class).value();
    }

    private static String mustBe(String name, Class<?> type) {
        return name + " must be " + kind(name, type);
    }

    private static String kind(String name, Class<?> type) {
        if (name.equals(HEARTBEAT)) {
            return "a non-negative integer";
        }
        if (type == Integer.class) {
            return "an integer";
        }
        return type == Map.class ? "a JSON object of strings" : "a string";
    }

    /** Prints no setting: one may be a credential. */
    @Override
    public String toString() {
        return "Settings[***]";
    }
}
