package com.epam.aidial.provider.core;

import com.epam.aidial.provider.api.ConfigException;
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
 * The defaults and the values derived from the {@link Settings}. The process environment is read by {@link #from(Map)}
 * and nowhere else, so an embedding process controls what the settings are.
 */
public final class Config {

    private static final Pattern RESPONSES_SUFFIX = Pattern.compile("/responses(?:/.*)?$");
    private static final Duration TEN_MINUTES = Duration.ofMinutes(10);
    private static final String DEFAULT_PORT = "5000";

    private final Settings settings;
    private final int port;
    private final Limits openai;
    private final Limits vertexai;
    private final Limits bedrock;
    private final @Nullable Map<String, String> sessionTags;
    private final Set<String> vllmDeployments;

    private Config(Settings settings, int port) {
        this.settings = settings;
        this.port = port;
        OptionalInt httpMax = optional(settings.httpMaxConnections());
        int httpSeconds = orElse(settings.httpPoolTimeout(), 10);
        int anthropicPool = orElse(settings.anthropicPoolTimeout(), 10);
        int requestTimeout = orElse(settings.requestTimeoutSeconds(), 600);
        // A pool timeout of 0 or less waits without limit for the openai and vertexai mounts, and fails at once for
        // the bedrock mount.
        PoolWait httpWait =
                httpSeconds > 0 ? new PoolWait.Bounded(Duration.ofSeconds(httpSeconds)) : new PoolWait.Unbounded();
        PoolWait anthropicWait =
                anthropicPool > 0 ? new PoolWait.Bounded(Duration.ofSeconds(anthropicPool)) : new PoolWait.FailFast();
        this.openai = Limits.capped(httpMax.orElse(1000), httpWait, TEN_MINUTES);
        this.vertexai = Limits.capped(httpMax.orElse(250), httpWait, TEN_MINUTES);
        this.bedrock = Limits.capped(
                orElse(settings.anthropicMaxConnections(), 1000),
                anthropicWait,
                Duration.ofSeconds(Math.max(requestTimeout, 0)));
        Map<String, String> tags = settings.awsSessionTags();
        this.sessionTags = tags == null || tags.isEmpty() ? null : tags;
        this.vllmDeployments = Stream.concat(list(settings.vllmDeployments()), list(settings.qwen3AsrVllmDeployments()))
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * The settings of a process environment, and its {@code PORT}.
     *
     * @throws ConfigException for the first setting that is refused
     */
    public static Config from(Map<String, String> environment) {
        int port = parsePort(environment.getOrDefault("PORT", DEFAULT_PORT));
        return new Config(Settings.from(environment), port);
    }

    /** For the settings that are not a process's: the port is the default one, as there is no server to listen. */
    public static Config from(Settings settings) {
        return new Config(settings, Integer.parseInt(DEFAULT_PORT));
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
        return orElse(settings.sseHeartbeatInterval(), 0);
    }

    /** Keeps the JSON object's order. */
    public @Nullable Map<String, String> sessionTags() {
        return sessionTags;
    }

    public Proxies proxies() {
        return new Proxies(settings.httpProxy(), settings.httpsProxy(), settings.noProxy());
    }

    public String anthropicBaseUrl() {
        return Urls.trimTrailingSlashes(orElse(settings.anthropicBaseUrl(), "https://api.anthropic.com"));
    }

    public String awsAccessKeyId() {
        return orElse(settings.awsAccessKeyId(), "");
    }

    public String awsSecretAccessKey() {
        return orElse(settings.awsSecretAccessKey(), "");
    }

    public String awsSessionToken() {
        return orElse(settings.awsSessionToken(), "");
    }

    public String awsAssumeRoleArn() {
        return orElse(settings.awsAssumeRoleArn(), "");
    }

    public String azureOpenAiScope() {
        return orElse(settings.azureOpenAiScope(), "https://cognitiveservices.azure.com/.default");
    }

    public String dialUrl() {
        String url = settings.dialUrl();
        return url == null || url.endsWith("/") ? url : url + "/";
    }

    public String anthropicVertexBaseUrl() {
        return Urls.trimTrailingSlashes(orElse(settings.anthropicVertexBaseUrl(), ""));
    }

    /** DEFAULT_REGION is the deprecated fallback; an empty value counts as unset. */
    public String gcpDefaultRegion() {
        String region = orElse(settings.gcpDefaultRegion(), "");
        return region.isEmpty() ? orElse(settings.defaultRegion(), "") : region;
    }

    public String gcpProjectId() {
        return orElse(settings.gcpProjectId(), "");
    }

    /** Null when unset; an empty value is returned as is, unlike in {@link #awsRegion}. */
    public String awsDefaultRegion() {
        return settings.awsDefaultRegion();
    }

    public String awsRegion() {
        String region = orElse(settings.awsRegion(), "");
        if (region.isEmpty()) {
            region = orElse(settings.awsDefaultRegion(), "");
        }
        return region.isEmpty() ? "us-east-1" : region;
    }

    public String awsClaudeDefaultClient() {
        return orElse(settings.awsClaudeDefaultClient(), "legacy");
    }

    public String anthropicBedrockBaseUrl() {
        return Urls.trimTrailingSlashes(orElse(settings.anthropicBedrockBaseUrl(), ""));
    }

    public String anthropicBedrockMantleBaseUrl() {
        return Urls.trimTrailingSlashes(orElse(settings.anthropicBedrockMantleBaseUrl(), ""));
    }

    public String awsBedrockBaseUrl() {
        return bedrockBaseUrl(orElse(settings.awsBedrockBaseUrl(), ""));
    }

    public String awsBearerTokenBedrock() {
        return orElse(settings.awsBearerTokenBedrock(), "");
    }

    public Set<String> vllmDeployments() {
        return vllmDeployments;
    }

    public String awsProfile() {
        return Strings.emptyToNull(settings.awsProfile());
    }

    public String awsStsEndpointUrl() {
        return Strings.emptyToNull(orElse(settings.awsEndpointUrlSts(), settings.awsEndpointUrl()));
    }

    public String googleApplicationCredentials() {
        return Strings.emptyToNull(settings.googleApplicationCredentials());
    }

    public String googleCloudQuotaProject() {
        return Strings.emptyToNull(settings.googleCloudQuotaProject());
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

    private static OptionalInt optional(@Nullable Integer value) {
        return value == null ? OptionalInt.empty() : OptionalInt.of(value);
    }

    private static int orElse(@Nullable Integer value, int defaultValue) {
        return value == null ? defaultValue : value;
    }

    private static String orElse(@Nullable String value, String defaultValue) {
        return value == null ? defaultValue : value;
    }
}
