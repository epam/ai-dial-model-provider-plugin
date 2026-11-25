package com.epam.aidial.provider.openai;

import com.epam.aidial.provider.api.AdapterRequest;
import com.epam.aidial.provider.api.InvalidRequestException;
import com.epam.aidial.provider.core.Config;
import com.epam.aidial.provider.credentials.Secret;
import com.epam.aidial.provider.upstream.UpstreamRefusedException;
import com.epam.aidial.provider.upstream.Urls;
import io.vertx.core.MultiMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

public final class OpenAiTargets {

    private static final String RESPONSES = "/responses";

    private static final Pattern MANTLE_ENDPOINT =
            Pattern.compile("^https?://bedrock-mantle\\.([a-z0-9-]+)\\.api\\.aws(?:/.*)?$");
    private static final Pattern RUNTIME_ENDPOINT =
            Pattern.compile("^https?://bedrock-runtime\\.([a-z0-9-]+)\\.amazonaws\\.com(?:/.*)?$");
    private static final Pattern AZURE_DEPLOYMENT_ENDPOINT = Pattern.compile("^(.+?)/openai/deployments/(.+)$");
    private static final Pattern AZURE_ENDPOINT = Pattern.compile("^(.+?)/openai$");

    private OpenAiTargets() {}

    /** Throws {@link UpstreamRefusedException} or {@link InvalidRequestException} when the request makes no client. */
    public interface Resolver {
        Target resolve(AdapterRequest request);
    }

    /**
     * The first match decides. Refusals come in this order: a missing X-Upstream-Endpoint, an extra data it rejects,
     * an endpoint that is no URL of the API, a versioned API without an api-version.
     */
    public static Resolver openai(Config config) {
        String mantleBase = config.awsBedrockBaseUrl();
        return request -> {
            MultiMap h = request.headers();
            String endpoint = request.endpoint();
            if (endpoint == null) {
                throw UpstreamRefusedException.missingEndpoint();
            }
            UpstreamExtraData extra = UpstreamExtraData.of(h);
            MultiMap headers = extra.proxied(h);
            if (extra.vendor() == UpstreamExtraData.Vendor.ALIBABA_CLOUD) {
                // Alibaba Cloud's Responses API has no cache_control markers: caching is asked per session.
                headers.set("X-Dashscope-Session-Cache", "enable");
            }
            if (!endpoint.endsWith(RESPONSES)) {
                throw UpstreamRefusedException.invalidEndpointFormat();
            }
            String base = endpoint.substring(0, endpoint.length() - RESPONSES.length());
            Shared shared = new Shared(headers, headers.contains("Authorization"), extra, Secret.of(request.key()));
            Matcher m = MANTLE_ENDPOINT.matcher(base);
            if (m.matches()) {
                // Only the endpoint's region counts: its scheme and path are not used.
                String region = m.group(1);
                String url =
                        mantleBase.isEmpty() ? "https://bedrock-mantle." + region + ".api.aws/openai/v1" : mantleBase;
                return shared.bedrock(url + RESPONSES, new Target.Bedrock(Target.BedrockApi.MANTLE, region));
            }
            m = RUNTIME_ENDPOINT.matcher(base);
            if (m.matches()) {
                return shared.bedrock(
                        Urls.trimTrailingSlashes(base) + RESPONSES,
                        new Target.Bedrock(Target.BedrockApi.RUNTIME, m.group(1)));
            }
            m = AZURE_DEPLOYMENT_ENDPOINT.matcher(base);
            if (!m.matches()) {
                m = AZURE_ENDPOINT.matcher(base);
            }
            if (!m.matches()) {
                return shared.v1(Urls.trimTrailingSlashes(base) + RESPONSES);
            }
            // A name that repeats keeps its last value. The SDK leaves the deployment out of a /responses URL.
            return shared.azure(
                    Urls.trimTrailingSlashes(m.group(1)) + "/openai" + RESPONSES, request.requiredApiVersion());
        };
    }

    private record Shared(MultiMap headers, boolean proxiedAuth, UpstreamExtraData extra, @Nullable Secret key) {

        Target bedrock(String baseUrl, Target.Bedrock bedrock) {
            return new Target(baseUrl, false, null, headers, proxiedAuth, extra, key, bedrock);
        }

        Target v1(String baseUrl) {
            return new Target(baseUrl, false, null, headers, proxiedAuth, extra, key, null);
        }

        Target azure(String baseUrl, String version) {
            return new Target(baseUrl, true, version, headers, proxiedAuth, extra, key, null);
        }
    }
}
