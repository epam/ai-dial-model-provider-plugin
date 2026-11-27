package com.epam.aidial.provider.anthropic;

import com.epam.aidial.provider.api.AdapterRequest;
import com.epam.aidial.provider.core.Config;
import com.epam.aidial.provider.credentials.AwsCredentialSettings;
import com.epam.aidial.provider.credentials.AwsCredentials;
import com.epam.aidial.provider.credentials.SessionTags;
import com.epam.aidial.provider.upstream.CredentialsUnavailableException;
import com.epam.aidial.provider.upstream.UpstreamRefusedException;
import com.epam.aidial.provider.upstream.Urls;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

public final class AnthropicTargets {

    private static final Pattern FOUNDRY_ENDPOINT = Pattern.compile("^(.*/anthropic)/v1/messages");
    private static final Pattern MESSAGES_ENDPOINT = Pattern.compile("^(.*?)/v1/messages");

    private AnthropicTargets() {}

    /** A configuration that makes no client throws an {@link UpstreamRefusedException}. */
    public interface Resolver {
        Target resolve(AdapterRequest request);
    }

    public static Resolver openai() {
        return request -> {
            String endpoint = request.endpoint();
            if (endpoint == null) {
                throw UpstreamRefusedException.missingEndpoint();
            }
            String key = request.key();
            Matcher m = MESSAGES_ENDPOINT.matcher(endpoint);
            if (!m.find()) {
                throw UpstreamRefusedException.invalidEndpointFormat();
            }
            String base = m.group(1);
            String baseUrl = Urls.trimTrailingSlashes(base);
            if (foundry(base)) {
                return Target.Foundry.of(baseUrl, key);
            }
            if (key == null) {
                // Foundry takes an Azure AD token; any other upstream needs a key: a client with no credentials cannot
                // be made.
                throw UpstreamRefusedException.missingKey();
            }
            return Target.Platform.of(baseUrl, key);
        };
    }

    public static boolean foundry(String base) {
        String host;
        try {
            host = new URI(base).getHost(); // null for a host name URI cannot parse, an underscore included
        } catch (URISyntaxException e) {
            host = null;
        }
        return host != null && host.toLowerCase(Locale.ROOT).contains("azure") && base.endsWith("/anthropic");
    }

    public static Resolver vertexai(Config config) {
        String anthropicBase = config.anthropicBaseUrl();
        String vertexBaseUrl = config.anthropicVertexBaseUrl();
        String defaultRegion = config.gcpDefaultRegion();
        String defaultProject = config.gcpProjectId();
        return request -> {
            String key = request.key();
            String endpoint = request.endpoint();
            if (endpoint != null) {
                Matcher m = FOUNDRY_ENDPOINT.matcher(endpoint);
                if (m.find()) {
                    return Target.Foundry.of(m.group(1), key);
                }
            }
            if (key != null) {
                return Target.Platform.of(anthropicBase, key);
            }
            UpstreamExtraData.Vertex extra = UpstreamExtraData.Vertex.of(request);
            String region = extra.regionOr(defaultRegion);
            String project = extra.projectOr(defaultProject);
            if (region.isEmpty() || project.isEmpty()) {
                throw UpstreamRefusedException.misconfigured(
                        "no region or project in X-Upstream-Extra-Data, DEFAULT_REGION or GCP_PROJECT_ID");
            }
            String base = vertexBaseUrl.isEmpty() ? vertexHost(region) : vertexBaseUrl;
            return new Target.Vertex(
                    base + "/projects/" + project + "/locations/" + region + "/publishers/anthropic/models");
        };
    }

    /**
     * Rejects a malformed extra data, a client other than legacy or mantle, no region anywhere, and an empty one for a
     * role, whose STS client has no endpoint then. An AWS configuration that does not load fails before any DIAL or
     * STS call.
     *
     * @param tags null when AWS_SESSION_TAGS tags nothing: an assumed role is then not tagged per request
     */
    public static Resolver bedrock(Config config, AwsCredentials credentials, SessionTags tags) {
        // In this process DEFAULT_REGION is the vertexai adapter's: the bedrock adapter reads AWS_DEFAULT_REGION only.
        String defaultRegion = config.awsDefaultRegion();
        BedrockSettings defaults = new BedrockSettings(
                defaultRegion == null ? "" : defaultRegion,
                config.awsClaudeDefaultClient(),
                AwsCredentialSettings.of(config));
        return new BedrockResolver(
                config.anthropicBaseUrl(),
                defaultRegion != null,
                defaults,
                config.anthropicBedrockBaseUrl(),
                config.anthropicBedrockMantleBaseUrl(),
                credentials,
                tags);
    }

    private record BedrockSettings(String region, String claudeClient, AwsCredentialSettings aws) {

        /** A credential member's null (already the empty string there) empties the setting. */
        BedrockSettings overlay(UpstreamExtraData.Bedrock extra) {
            return new BedrockSettings(
                    or(extra.region(), region), or(extra.claudeClient(), claudeClient), aws.overlay(extra.aws()));
        }

        private static String or(@Nullable String member, String current) {
            return member == null ? current : member;
        }
    }

    private static final class BedrockResolver implements Resolver {

        private final String anthropicBase;
        private final boolean regionConfigured;
        private final BedrockSettings defaults;
        private final String invokeBase;
        private final String mantleBase;
        private final AwsCredentials credentials;
        private final SessionTags tags;

        BedrockResolver(
                String anthropicBase,
                boolean regionConfigured,
                BedrockSettings defaults,
                String invokeBase,
                String mantleBase,
                AwsCredentials credentials,
                SessionTags tags) {
            this.anthropicBase = anthropicBase;
            this.regionConfigured = regionConfigured;
            this.defaults = defaults;
            this.invokeBase = invokeBase;
            this.mantleBase = mantleBase;
            this.credentials = credentials;
            this.tags = tags;
        }

        @Override
        public Target resolve(AdapterRequest request) {
            String key = request.key();
            if (key != null) {
                return Target.Platform.of(anthropicBase, key);
            }
            UpstreamExtraData.Bedrock extra = UpstreamExtraData.Bedrock.of(request);
            BedrockSettings settings = defaults.overlay(extra);
            BedrockClient client = BedrockClient.fromSetting(settings.claudeClient())
                    .orElseThrow(() ->
                            UpstreamRefusedException.misconfigured("the Claude client is neither legacy nor mantle"));
            if (!regionConfigured && extra.region() == null) {
                throw UpstreamRefusedException.misconfigured(
                        "no region in X-Upstream-Extra-Data or AWS_DEFAULT_REGION");
            }
            AwsCredentials.CredentialSet set = settings.aws().credentialSet();
            String region = settings.region();
            if (!set.roleArn().isEmpty() && region.isEmpty()) {
                throw UpstreamRefusedException.misconfigured("an empty region for a role to assume");
            }
            return bedrock(client, region, set);
        }

        private String baseUrl(BedrockClient client, String region) {
            return switch (client) {
                case LEGACY ->
                    invokeBase.isEmpty() ? "https://bedrock-runtime." + region + ".amazonaws.com" : invokeBase;
                case MANTLE ->
                    mantleBase.isEmpty() ? "https://bedrock-mantle." + region + ".api.aws/anthropic" : mantleBase;
            };
        }

        private Target.Bedrock bedrock(BedrockClient client, String region, AwsCredentials.CredentialSet set) {
            String base = baseUrl(client, region);
            boolean role = !set.roleArn().isEmpty();
            if (role && tags != null) {
                // The profile first: a request that cannot get the role's credentials makes no DIAL call.
                BedrockCredentials.TaggedRole tagged = (headers, scope, model) -> credentials
                        .checkProfile(CredentialsUnavailableException.Source.AWS_ROLE, scope.aborted())
                        .compose(v -> credentials.taggedProvider(
                                set,
                                region,
                                client.setting(),
                                tags.resolve(headers, scope, model),
                                AwsCredentials.BEDROCK_ROLE_WINDOW));
                return new Target.Bedrock(client, base, region, new BedrockCredentials.TaggedPerRequest(tagged));
            }
            AwsCredentials.Provider provider = credentials.provider(
                    set, region, client.setting(), AwsCredentials.Session.NONE, AwsCredentials.BEDROCK_ROLE_WINDOW);
            return new Target.Bedrock(client, base, region, new BedrockCredentials.Fixed(provider));
        }
    }

    /** As the Anthropic SDK picks the host for a region. */
    static String vertexHost(String region) {
        return switch (region) {
            case "global" -> "https://aiplatform.googleapis.com/v1";
            case "us" -> "https://aiplatform.us.rep.googleapis.com/v1";
            case "eu" -> "https://aiplatform.eu.rep.googleapis.com/v1";
            default -> "https://" + region + "-aiplatform.googleapis.com/v1";
        };
    }
}
