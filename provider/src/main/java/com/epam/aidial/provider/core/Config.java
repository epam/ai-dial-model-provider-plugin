package com.epam.aidial.provider.core;

import com.epam.aidial.provider.api.ConfigException;
import com.epam.aidial.provider.json.Json;
import com.epam.aidial.provider.upstream.Limits;
import com.epam.aidial.provider.upstream.Limits.PoolWait;
import com.epam.aidial.provider.upstream.Proxies;
import com.epam.aidial.provider.upstream.Urls;
import com.google.common.base.Strings;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Settings come from a map and never from the environment here, so an embedding process controls them. The checked
 * settings are parsed in a fixed order and the first one refused is reported.
 */
public final class Config {

    private static final Pattern RESPONSES_SUFFIX = Pattern.compile("/responses(?:/.*)?$");
    private static final Duration TEN_MINUTES = Duration.ofMinutes(10);

    private final Map<String, String> settings;
    private final int port;
    private final Limits openai;
    private final Limits vertexai;
    private final Limits bedrock;
    private final int heartbeatSeconds;
    private final @Nullable Map<String, String> sessionTags;
    private final Set<String> vllmDeployments;

    private Config(
            Map<String, String> settings,
            int port,
            Limits openai,
            Limits vertexai,
            Limits bedrock,
            int heartbeatSeconds,
            @Nullable Map<String, String> sessionTags) {
        this.settings = settings;
        this.port = port;
        this.openai = openai;
        this.vertexai = vertexai;
        this.bedrock = bedrock;
        this.heartbeatSeconds = heartbeatSeconds;
        this.sessionTags = sessionTags;
        this.vllmDeployments = Stream.concat(
                        list(settings.get("VLLM_DEPLOYMENTS")), list(settings.get("QWEN3_ASR_VLLM_DEPLOYMENTS")))
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * @throws ConfigException for the first checked setting that is refused
     */
    public static Config from(Map<String, String> settings) {
        Map<String, String> s = Map.copyOf(settings);
        int port = parsePort(s.getOrDefault("PORT", "5000"));
        OptionalInt httpMax = optionalInt(s, "HTTP_MAX_CONNECTIONS");
        OptionalInt httpPool = optionalInt(s, "HTTP_POOL_TIMEOUT");
        int anthropicMax = optionalInt(s, "ANTHROPIC_MAX_CONNECTIONS").orElse(1000);
        int anthropicPool = optionalInt(s, "ANTHROPIC_POOL_TIMEOUT").orElse(10);
        int requestTimeout = optionalInt(s, "REQUEST_TIMEOUT_SECONDS").orElse(600);
        int heartbeat = heartbeat(s.getOrDefault("SSE_HEARTBEAT_INTERVAL", "0"));
        Map<String, String> tags =
                stringDict("AWS_SESSION_TAGS", s.get("AWS_SESSION_TAGS"), "must be a JSON object of strings");

        // A pool timeout of 0 or less waits without limit for the openai and vertexai mounts, and fails at once for
        // the bedrock mount.
        int httpSeconds = httpPool.orElse(10);
        PoolWait httpWait =
                httpSeconds > 0 ? new PoolWait.Bounded(Duration.ofSeconds(httpSeconds)) : new PoolWait.Unbounded();
        PoolWait anthropicWait =
                anthropicPool > 0 ? new PoolWait.Bounded(Duration.ofSeconds(anthropicPool)) : new PoolWait.FailFast();
        return new Config(
                s,
                port,
                Limits.capped(httpMax.orElse(1000), httpWait, TEN_MINUTES),
                Limits.capped(httpMax.orElse(250), httpWait, TEN_MINUTES),
                Limits.capped(anthropicMax, anthropicWait, Duration.ofSeconds(Math.max(requestTimeout, 0))),
                heartbeat,
                tags == null || tags.isEmpty() ? null : tags);
    }

    public int port() {
        return port;
    }

    public Limits openai() {
        return openai;
    }

    public Limits vertexai() {
        return vertexai;
    }

    public Limits bedrock() {
        return bedrock;
    }

    /** Whole seconds; 0 for none. */
    public int heartbeatSeconds() {
        return heartbeatSeconds;
    }

    /** Keeps the JSON object's order. */
    public @Nullable Map<String, String> sessionTags() {
        return sessionTags;
    }

    public Proxies proxies() {
        return new Proxies(settings::get);
    }

    private String get(String name) {
        return settings.get(name);
    }

    private String get(String name, String defaultValue) {
        return settings.getOrDefault(name, defaultValue);
    }

    public String anthropicBaseUrl() {
        return Urls.trimTrailingSlashes(get("ANTHROPIC_BASE_URL", "https://api.anthropic.com"));
    }

    public String awsAccessKeyId() {
        return get("AWS_ACCESS_KEY_ID", "");
    }

    public String awsSecretAccessKey() {
        return get("AWS_SECRET_ACCESS_KEY", "");
    }

    public String awsSessionToken() {
        return get("AWS_SESSION_TOKEN", "");
    }

    public String awsAssumeRoleArn() {
        return get("AWS_ASSUME_ROLE_ARN", "");
    }

    public String azureOpenAiScope() {
        return get("AZURE_OPEN_AI_SCOPE", "https://cognitiveservices.azure.com/.default");
    }

    public String dialUrl() {
        String url = get("DIAL_URL");
        return url == null || url.endsWith("/") ? url : url + "/";
    }

    public String anthropicVertexBaseUrl() {
        return Urls.trimTrailingSlashes(get("ANTHROPIC_VERTEX_BASE_URL", ""));
    }

    /** DEFAULT_REGION is the deprecated fallback; an empty value counts as unset. */
    public String gcpDefaultRegion() {
        String region = get("GCP_DEFAULT_REGION", "");
        return region.isEmpty() ? get("DEFAULT_REGION", "") : region;
    }

    public String gcpProjectId() {
        return get("GCP_PROJECT_ID", "");
    }

    /** Null when unset; an empty value is returned as is, unlike in {@link #awsRegion}. */
    public String awsDefaultRegion() {
        return get("AWS_DEFAULT_REGION");
    }

    public String awsRegion() {
        String region = get("AWS_REGION", "");
        if (region.isEmpty()) {
            region = get("AWS_DEFAULT_REGION", "");
        }
        return region.isEmpty() ? "us-east-1" : region;
    }

    public String awsClaudeDefaultClient() {
        return get("AWS_CLAUDE_DEFAULT_CLIENT", "legacy");
    }

    public String anthropicBedrockBaseUrl() {
        return Urls.trimTrailingSlashes(get("ANTHROPIC_BEDROCK_BASE_URL", ""));
    }

    public String anthropicBedrockMantleBaseUrl() {
        return Urls.trimTrailingSlashes(get("ANTHROPIC_BEDROCK_MANTLE_BASE_URL", ""));
    }

    public String awsBedrockBaseUrl() {
        return bedrockBaseUrl(get("AWS_BEDROCK_BASE_URL", ""));
    }

    public String awsBearerTokenBedrock() {
        return get("AWS_BEARER_TOKEN_BEDROCK", "");
    }

    public Set<String> vllmDeployments() {
        return vllmDeployments;
    }

    public String awsProfile() {
        return Strings.emptyToNull(get("AWS_PROFILE"));
    }

    public String awsStsEndpointUrl() {
        return Strings.emptyToNull(get("AWS_ENDPOINT_URL_STS", get("AWS_ENDPOINT_URL")));
    }

    public String googleApplicationCredentials() {
        return Strings.emptyToNull(get("GOOGLE_APPLICATION_CREDENTIALS"));
    }

    public String googleCloudQuotaProject() {
        return Strings.emptyToNull(get("GOOGLE_CLOUD_QUOTA_PROJECT"));
    }

    private static String bedrockBaseUrl(String value) {
        String v = value.strip();
        URI uri;
        try {
            uri = new URI(v);
        } catch (URISyntaxException e) {
            return Urls.trimTrailingSlashes(v);
        }
        if (uri.getScheme() == null || uri.getRawAuthority() == null) {
            return Urls.trimTrailingSlashes(v);
        }
        String path = Urls.trimTrailingSlashes(uri.getRawPath());
        Matcher m = RESPONSES_SUFFIX.matcher(path);
        if (m.find()) {
            path = path.substring(0, m.start());
        }
        StringBuilder out = new StringBuilder(uri.getScheme())
                .append("://")
                .append(uri.getRawAuthority())
                .append(path);
        if (uri.getRawQuery() != null) {
            out.append('?').append(uri.getRawQuery());
        }
        if (uri.getRawFragment() != null) {
            out.append('#').append(uri.getRawFragment());
        }
        return out.toString();
    }

    private static Stream<String> list(@Nullable String value) {
        return value == null
                ? Stream.empty()
                : Arrays.stream(value.split(",", -1)).map(String::strip);
    }

    private static int parsePort(String value) {
        String message = "PORT must be an integer from 0 to 65535";
        int port;
        try {
            port = Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new ConfigException("PORT", message, e);
        }
        if (port < 0 || port > 65535) {
            throw new ConfigException("PORT", message);
        }
        return port;
    }

    private static OptionalInt optionalInt(Map<String, String> s, String name) {
        String value = s.get(name);
        if (value == null) {
            return OptionalInt.empty();
        }
        try {
            return OptionalInt.of(Integer.parseInt(value));
        } catch (NumberFormatException e) {
            throw new ConfigException(name, name + " must be an integer", e);
        }
    }

    private static int heartbeat(String value) {
        String message = "SSE_HEARTBEAT_INTERVAL must be a non-negative integer";
        int seconds;
        try {
            seconds = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new ConfigException("SSE_HEARTBEAT_INTERVAL", message, e);
        }
        if (seconds < 0) {
            throw new ConfigException("SSE_HEARTBEAT_INTERVAL", message);
        }
        return seconds;
    }

    private static Map<String, String> stringDict(String name, String value, String mustBe) {
        if (value == null) {
            return null;
        }
        try {
            return Json.parseStringMap(value);
        } catch (Json.ParseException e) {
            throw new ConfigException(name, name + " " + mustBe, e);
        }
    }
}
