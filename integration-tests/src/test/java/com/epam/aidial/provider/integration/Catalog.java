package com.epam.aidial.provider.integration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every model under test and every way to reach it, as DIAL Core would send it: the headers that choose the upstream
 * and its credentials. The suites and the smoke report both read it, so a model added here is tested and reported.
 *
 * <p>Claude is reached on Bedrock with both clients (invoke-model, {@code legacy}, and {@code mantle}), on Vertex AI,
 * and on Azure AI Foundry; GPT through the Responses API on Bedrock Mantle and Azure OpenAI, and through the Messages
 * API too, which the service translates to the Responses API of the same upstream. Azure goes twice, by API key and by
 * {@code DefaultAzureCredential}, which on a developer machine falls back to {@code az login}. Vertex AI has no
 * Responses API here.
 */
final class Catalog {

    // The model ids of the clouds: a Bedrock inference profile for invoke-model, a bare model id for Mantle.
    private static final List<String> BEDROCK_INVOKE_CLAUDE = List.of(
            "global.anthropic.claude-fable-5-2",
            "global.anthropic.claude-opus-5-5",
            "global.anthropic.claude-sonnet-5-5",
            "global.anthropic.claude-haiku-5-5");
    private static final List<String> BEDROCK_MANTLE_CLAUDE =
            List.of("anthropic.claude-fable-5", "anthropic.claude-opus-5-5", "anthropic.claude-sonnet-5");
    private static final List<String> BEDROCK_MANTLE_GPT =
            List.of("openai.gpt-6.1-sol", "openai.gpt-6-luna", "openai.gpt-5.5", "openai.gpt-5.4");
    // The newest Claude models are served from Vertex AI's global endpoint, not from every region. Not listed: the
    // Fable models, which the project has no quota for (429 on the global endpoint).
    private static final String VERTEX_REGION = "global";
    private static final List<String> VERTEX_CLAUDE =
            List.of("claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5");
    // The names of the deployments of the Azure resource.
    private static final List<String> AZURE_CLAUDE = List.of("claude-sonnet-4-520250929");
    private static final List<String> AZURE_GPT = List.of("gpt-6-luna", "gpt-5.4-2026-03-05", "gpt-5.2");

    private Catalog() {}

    static List<Deployment> of(Cloud cloud) {
        return switch (cloud) {
            case AWS -> aws();
            case GCP -> gcp();
            case AZURE -> azure();
        };
    }

    private static List<Deployment> aws() {
        String region = Env.require("AWS_DEFAULT_REGION");
        List<Deployment> out = new ArrayList<>();
        BEDROCK_INVOKE_CLAUDE.forEach(model -> out.add(claudeOnBedrock("legacy", region, model)));
        BEDROCK_MANTLE_CLAUDE.forEach(model -> out.add(claudeOnBedrock("mantle", region, model)));
        // No key and no extra data: the AWS profile of the settings signs the call.
        String mantle = "https://bedrock-mantle.%s.api.aws/v1/responses".formatted(region);
        Map<String, String> mantleHeaders = Map.of("X-Upstream-Endpoint", mantle);
        BEDROCK_MANTLE_GPT.forEach(model -> {
            out.add(responses(Cloud.AWS, "mantle", model, mantleHeaders));
            out.add(messagesOverResponses(Cloud.AWS, "mantle", model, mantleHeaders));
        });
        return out;
    }

    private static Deployment claudeOnBedrock(String client, String region, String model) {
        String extra = """
                {"region": "%s", "claude_client": "%s"}""".formatted(region, client);
        return messages(Cloud.AWS, client, "bedrock", model, Map.of("X-Upstream-Extra-Data", extra));
    }

    private static List<Deployment> gcp() {
        String extra = """
                {"region": "%s", "project": "%s"}"""
                .formatted(VERTEX_REGION, Env.require("GCP_PROJECT_ID"));
        return VERTEX_CLAUDE.stream()
                .map(model -> messages(Cloud.GCP, "adc", "vertexai", model, Map.of("X-Upstream-Extra-Data", extra)))
                .toList();
    }

    private static List<Deployment> azure() {
        Env.Azure azure = Env.azure();
        List<Deployment> out = new ArrayList<>();
        // The key goes in X-Upstream-Key; without one the service takes an Azure AD token.
        for (Map.Entry<String, String> auth :
                Map.of("api-key", azure.key(), "default-credential", "").entrySet()) {
            for (String model : AZURE_CLAUDE) {
                Map<String, String> headers = azureHeaders(azure.resource() + "/anthropic/v1/messages", auth);
                out.add(messages(Cloud.AZURE, auth.getKey(), "openai", model, headers));
            }
            for (String model : AZURE_GPT) {
                Map<String, String> headers = azureHeaders(azure.resource() + "/openai/v1/responses", auth);
                out.add(responses(Cloud.AZURE, auth.getKey(), model, headers));
                out.add(messagesOverResponses(Cloud.AZURE, auth.getKey(), model, headers));
            }
        }
        return out;
    }

    private static Map<String, String> azureHeaders(String endpoint, Map.Entry<String, String> auth) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Upstream-Endpoint", endpoint);
        if (!auth.getValue().isEmpty()) {
            headers.put("X-Upstream-Key", auth.getValue());
        }
        return headers;
    }

    /** The Messages API of a mount (openai, vertexai or bedrock). */
    private static Deployment messages(Cloud cloud, String via, String mount, String model, Map<String, String> h) {
        return new Deployment(cloud, Api.MESSAGES, via, model, "/compat/" + mount + "/anthropic/v1/messages", h);
    }

    /**
     * The Messages API of a model that has the Responses API only: the openai mount translates it when the endpoint
     * is a Responses one. The translation names the deployment in the answer, as DIAL Core tells it to in {@code
     * X-DIAL-DEPLOYMENT-ID}.
     */
    private static Deployment messagesOverResponses(Cloud cloud, String via, String model, Map<String, String> h) {
        Map<String, String> headers = new LinkedHashMap<>(h);
        headers.put("X-DIAL-DEPLOYMENT-ID", model);
        return messages(cloud, via + " (translated)", "openai", model, headers);
    }

    private static Deployment responses(Cloud cloud, String via, String model, Map<String, String> headers) {
        return new Deployment(cloud, Api.RESPONSES, via, model, "/compat/openai/openai/v1/responses", headers);
    }
}
