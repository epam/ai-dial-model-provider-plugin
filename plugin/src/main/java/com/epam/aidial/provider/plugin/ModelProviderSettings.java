package com.epam.aidial.provider.plugin;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A model-provider entry's settings: the {@code adapter} the entry serves, and the variables of the service's own
 * configuration by their names. A variable the entry leaves out comes from DIAL Core's environment, and one the entry
 * sets to {@code null} is unset. A name the plugin does not list is a variable too: the variables the service reads in
 * lower case ({@code http_proxy}) and the ones a newer service reads.
 */
@JsonClassDescription("A model provider: the adapter an entry serves, and the variables of its configuration.")
@JsonInclude(JsonInclude.Include.NON_NULL)
record ModelProviderSettings(
        @JsonProperty(value = "adapter", required = true)
                @JsonPropertyDescription(
                        "The cloud API the entry serves: openai (Azure OpenAI and OpenAI), vertexai (Anthropic on Vertex AI) or bedrock (Anthropic on Amazon Bedrock).")
                Adapter adapter,
        @JsonProperty("HTTP_MAX_CONNECTIONS")
                @JsonPropertyDescription(
                        "Upstream calls in flight per adapter. Adapters: openai, vertexai. Default: 1000 for openai, 250 for vertexai.")
                @Nullable
                Variable httpMaxConnections,
        @JsonProperty("HTTP_POOL_TIMEOUT")
                @JsonPropertyDescription(
                        "Seconds a call waits for a free slot before it fails with 503; 0 waits without limit. Adapters: openai, vertexai. Default: 10.")
                @Nullable
                Variable httpPoolTimeout,
        @JsonProperty("ANTHROPIC_MAX_CONNECTIONS")
                @JsonPropertyDescription("Upstream calls in flight. Adapter: bedrock. Default: 1000.")
                @Nullable
                Variable anthropicMaxConnections,
        @JsonProperty("ANTHROPIC_POOL_TIMEOUT")
                @JsonPropertyDescription(
                        "Seconds a call waits for a free slot before it fails with 503; 0 or less fails at once. Adapter: bedrock. Default: 10.")
                @Nullable
                Variable anthropicPoolTimeout,
        @JsonProperty("REQUEST_TIMEOUT_SECONDS")
                @JsonPropertyDescription(
                        "Seconds to wait for response headers and for each body read. Adapter: bedrock. Default: 600.")
                @Nullable
                Variable requestTimeoutSeconds,
        @JsonProperty("SSE_HEARTBEAT_INTERVAL")
                @JsonPropertyDescription(
                        "Seconds of silence in a Responses stream before a ': ping' comment; 0 turns it off. Adapter: openai. Default: 0.")
                @Nullable
                Variable sseHeartbeatInterval,
        @JsonProperty("HTTP_PROXY")
                @JsonPropertyDescription("Proxy URL for upstream calls over HTTP. All adapters.")
                @Nullable
                Variable httpProxy,
        @JsonProperty("HTTPS_PROXY")
                @JsonPropertyDescription("Proxy URL for upstream calls over HTTPS. All adapters.")
                @Nullable
                Variable httpsProxy,
        @JsonProperty("NO_PROXY")
                @JsonPropertyDescription(
                        "Comma-separated hosts that upstream calls reach without the proxy. All adapters.")
                @Nullable
                Variable noProxy,
        @JsonProperty("DIAL_URL")
                @JsonPropertyDescription("DIAL Core URL, for the user info of the AWS session tags. All adapters.")
                @Nullable
                Variable dialUrl,
        @JsonProperty("AZURE_OPEN_AI_SCOPE")
                @JsonPropertyDescription(
                        "Scope of the Azure AD token. Adapter: openai. Default: https://cognitiveservices.azure.com/.default.")
                @Nullable
                Variable azureOpenAiScope,
        @JsonProperty("AWS_BEDROCK_BASE_URL")
                @JsonPropertyDescription("Base URL of Bedrock Mantle, for the Responses API. Adapter: openai.")
                @Nullable
                Variable awsBedrockBaseUrl,
        @JsonProperty("AWS_BEARER_TOKEN_BEDROCK")
                @JsonPropertyDescription("Bearer token of Bedrock Mantle, for the Responses API. Adapter: openai.")
                @Nullable
                Variable awsBearerTokenBedrock,
        @JsonProperty("VLLM_DEPLOYMENTS")
                @JsonPropertyDescription(
                        "Comma-separated models that need an X-Upstream-Key, else the call fails with 401. Adapter: openai.")
                @Nullable
                Variable vllmDeployments,
        @JsonProperty("QWEN3_ASR_VLLM_DEPLOYMENTS")
                @JsonPropertyDescription(
                        "Comma-separated models that need an X-Upstream-Key, else the call fails with 401. Adapter: openai.")
                @Nullable
                Variable qwen3AsrVllmDeployments,
        @JsonProperty("ANTHROPIC_BASE_URL")
                @JsonPropertyDescription(
                        "Anthropic API used with a key. Adapters: vertexai, bedrock. Default: https://api.anthropic.com.")
                @Nullable
                Variable anthropicBaseUrl,
        @JsonProperty("GCP_DEFAULT_REGION")
                @JsonPropertyDescription("Region when the model's extraData has none. Adapter: vertexai.")
                @Nullable
                Variable gcpDefaultRegion,
        @JsonProperty("GCP_PROJECT_ID")
                @JsonPropertyDescription("Project when the model's extraData has none. Adapter: vertexai.")
                @Nullable
                Variable gcpProjectId,
        @JsonProperty("ANTHROPIC_VERTEX_BASE_URL")
                @JsonPropertyDescription("Vertex AI base URL. Adapter: vertexai. Default: per region.")
                @Nullable
                Variable anthropicVertexBaseUrl,
        @JsonProperty("GOOGLE_APPLICATION_CREDENTIALS")
                @JsonPropertyDescription(
                        "Path of the application default credentials, or of an AWS workload identity file. Adapter: vertexai.")
                @Nullable
                Variable googleApplicationCredentials,
        @JsonProperty("GOOGLE_CLOUD_QUOTA_PROJECT")
                @JsonPropertyDescription("Quota project of the credentials. Adapter: vertexai.")
                @Nullable
                Variable googleCloudQuotaProject,
        @JsonProperty("AWS_DEFAULT_REGION")
                @JsonPropertyDescription("Region when the model's extraData has none. Adapter: bedrock.")
                @Nullable
                Variable awsDefaultRegion,
        @JsonProperty("AWS_CLAUDE_DEFAULT_CLIENT")
                @JsonPropertyDescription(
                        "How Claude is called: legacy (invoke-model) or mantle. Adapter: bedrock. Default: legacy.")
                @Nullable
                Variable awsClaudeDefaultClient,
        @JsonProperty("ANTHROPIC_BEDROCK_BASE_URL")
                @JsonPropertyDescription("Base URL of invoke-model. Adapter: bedrock. Default: per region.")
                @Nullable
                Variable anthropicBedrockBaseUrl,
        @JsonProperty("ANTHROPIC_BEDROCK_MANTLE_BASE_URL")
                @JsonPropertyDescription("Base URL of Mantle. Adapter: bedrock. Default: per region.")
                @Nullable
                Variable anthropicBedrockMantleBaseUrl,
        @JsonProperty("AWS_ACCESS_KEY_ID")
                @JsonPropertyDescription(
                        "AWS credentials when the model's extraData has none, else the SDK's default chain. Adapter: bedrock. Keep credentials in DIAL Core's environment.")
                @Nullable
                Variable awsAccessKeyId,
        @JsonProperty("AWS_SECRET_ACCESS_KEY")
                @JsonPropertyDescription(
                        "AWS credentials when the model's extraData has none, else the SDK's default chain. Adapter: bedrock. Keep credentials in DIAL Core's environment.")
                @Nullable
                Variable awsSecretAccessKey,
        @JsonProperty("AWS_SESSION_TOKEN")
                @JsonPropertyDescription(
                        "AWS credentials when the model's extraData has none, else the SDK's default chain. Adapter: bedrock. Keep credentials in DIAL Core's environment.")
                @Nullable
                Variable awsSessionToken,
        @JsonProperty("AWS_ASSUME_ROLE_ARN")
                @JsonPropertyDescription(
                        "Role to assume with the AWS credentials when the model's extraData has none. Adapter: bedrock.")
                @Nullable
                Variable awsAssumeRoleArn,
        @JsonProperty("AWS_ENDPOINT_URL_STS")
                @JsonPropertyDescription("STS endpoint for assuming roles. Adapter: bedrock.")
                @Nullable
                Variable awsEndpointUrlSts,
        @JsonProperty("AWS_ENDPOINT_URL")
                @JsonPropertyDescription(
                        "STS endpoint for assuming roles, when AWS_ENDPOINT_URL_STS is not set. Adapter: bedrock.")
                @Nullable
                Variable awsEndpointUrl,
        @JsonProperty("AWS_SESSION_TAGS")
                @JsonPropertyDescription(
                        "Tags of the session of an assumed role: an object of tag keys to Bedrock.modelId or UserInfo.<path>, or its JSON as a string. Adapter: bedrock.")
                @Nullable
                Variable awsSessionTags,
        @JsonAnySetter @JsonAnyGetter Map<String, Variable> others) {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, String>> TEXTS = new TypeReference<>() {};

    static ModelProviderSettings of(ObjectNode settings) {
        return MAPPER.convertValue(settings, ModelProviderSettings.class);
    }

    /** The environment, then the entry's variables: a variable the entry sets to null is removed. */
    Map<String, String> variables(Map<String, String> environment) {
        Map<String, String> variables = new HashMap<>(environment);
        Map<String, String> entry = MAPPER.convertValue(this, TEXTS);
        entry.remove("adapter");
        entry.forEach((name, text) -> {
            if (text == null) {
                variables.remove(name);
            } else {
                variables.put(name, text);
            }
        });
        return variables;
    }
}
