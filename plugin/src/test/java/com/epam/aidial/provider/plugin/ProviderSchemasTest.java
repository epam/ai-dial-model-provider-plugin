package com.epam.aidial.provider.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.epam.aidial.provider.ModelProviders;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The schemas DIAL Core checks the settings of an entry against, before it asks the provider to validate them. */
class ProviderSchemasTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ModelProviderPluginProvider modelProviderPlugin = new ModelProviderPluginProvider();
    private final TranslatorPluginProvider translatorPlugin = new TranslatorPluginProvider();
    private final ObjectNode modelProvider = modelProviderPlugin.settingsSchema();
    private final ObjectNode translator = translatorPlugin.settingsSchema();

    private static List<String> errors(ObjectNode settingsSchema, String settings) throws Exception {
        Schema schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_7)
                .getSchema(MAPPER.writeValueAsString(settingsSchema));
        return schema.validate(MAPPER.readTree(settings)).stream()
                .map(error -> error.getInstanceLocation() + ": " + error.getMessage())
                .toList();
    }

    @Test
    void providersDescribeThemselves() {
        assertEquals("model-provider", modelProviderPlugin.type());
        assertEquals("translator", translatorPlugin.type());
    }

    @Test
    void schemasAreSelfContainedDraft07() {
        for (ObjectNode schema : List.of(modelProvider, translator)) {
            assertEquals(
                    "http://json-schema.org/draft-07/schema#",
                    schema.get("$schema").asText());
            assertFalse(schema.toString().contains("$ref"));
        }
    }

    @Test
    void everyVariableIsDescribed() {
        JsonNode properties = modelProvider.get("properties");
        assertEquals(39, properties.size());
        properties.forEach(property -> assertTrue(property.hasNonNull("description"), property.toString()));
    }

    @Test
    void adaptersAreTheOnesThereAre() {
        JsonNode adapter = modelProvider.get("properties").get("adapter");
        assertEquals(
                ModelProviders.ADAPTERS, MAPPER.convertValue(adapter.get("enum"), new TypeReference<Set<String>>() {}));
    }

    @Test
    void modelProviderEntryNamesAnAdapter() throws Exception {
        for (String adapter : ModelProviders.ADAPTERS) {
            assertEquals(List.of(), errors(modelProvider, "{\"adapter\": \"%s\"}".formatted(adapter)), adapter);
        }
        assertEquals(List.of(": required property 'adapter' not found"), errors(modelProvider, "{}"));
        assertEquals(
                List.of("/adapter: does not have a value in the enumeration [\"openai\", \"vertexai\", \"bedrock\"]"),
                errors(modelProvider, "{\"adapter\": \"nope\"}"));
        String enumeration =
                "/adapter: does not have a value in the enumeration [\"openai\", \"vertexai\", \"bedrock\"]";
        assertEquals(
                List.of("/adapter: integer found, string expected", enumeration),
                errors(modelProvider, "{\"adapter\": 1}"));
        assertEquals(
                List.of("/adapter: null found, string expected", enumeration),
                errors(modelProvider, "{\"adapter\": null}"));
    }

    @Test
    void everySettingHasOneTypeAndNoOtherSettingIsAccepted() throws Exception {
        JsonNode schema = modelProvider;
        assertEquals(false, schema.get("additionalProperties").asBoolean(true));
        schema.get("properties")
                .forEach(property -> assertTrue(property.get("type").isTextual(), property.toString()));
        assertEquals(
                "integer",
                schema.get("properties").get("HTTP_MAX_CONNECTIONS").get("type").asText());
        assertEquals(
                "string", schema.get("properties").get("DIAL_URL").get("type").asText());
        JsonNode tags = schema.get("properties").get("AWS_SESSION_TAGS");
        assertEquals("object", tags.get("type").asText());
        assertEquals("string", tags.get("additionalProperties").get("type").asText());
    }

    @Test
    void modelProviderSettingsHaveTheirTypes() throws Exception {
        String settings =
                """
                {"adapter": "bedrock", "HTTP_MAX_CONNECTIONS": 10, "HTTP_POOL_TIMEOUT": 10, "NO_PROXY": "a,b",
                 "AWS_SESSION_TAGS": {"team": "UserInfo.team"}, "http_proxy": "http://proxy"}
                """;
        assertEquals(List.of(), errors(modelProvider, settings));
        assertEquals(
                List.of("/HTTP_MAX_CONNECTIONS: string found, integer expected"),
                errors(modelProvider, "{\"adapter\": \"openai\", \"HTTP_MAX_CONNECTIONS\": \"10\"}"));
        assertEquals(
                List.of("/HTTP_MAX_CONNECTIONS: array found, integer expected"),
                errors(modelProvider, "{\"adapter\": \"openai\", \"HTTP_MAX_CONNECTIONS\": [1]}"));
        assertEquals(
                List.of("/DIAL_URL: integer found, string expected"),
                errors(modelProvider, "{\"adapter\": \"openai\", \"DIAL_URL\": 1}"));
        assertEquals(
                List.of("/AWS_SESSION_TAGS/team: integer found, string expected"),
                errors(modelProvider, "{\"adapter\": \"bedrock\", \"AWS_SESSION_TAGS\": {\"team\": 1}}"));
        assertEquals(
                List.of("/AWS_SESSION_TAGS: string found, object expected"),
                errors(modelProvider, "{\"adapter\": \"bedrock\", \"AWS_SESSION_TAGS\": \"{}\"}"));
        assertEquals(
                List.of(
                        ": property 'SOMETHING_NEW' is not defined in the schema and the schema does not allow additional properties"),
                errors(modelProvider, "{\"adapter\": \"openai\", \"SOMETHING_NEW\": \"x\"}"));
    }

    @Test
    void translatorEntryNamesItsDirectionAndNothingElse() throws Exception {
        assertEquals(List.of(), errors(translator, "{\"direction\": \"anthropicMessagesToOpenaiResponses\"}"));
        assertEquals(List.of(": required property 'direction' not found"), errors(translator, "{}"));
        assertEquals(
                List.of("/direction: must be the constant value 'anthropicMessagesToOpenaiResponses'"),
                errors(translator, "{\"direction\": \"openaiResponsesToAnthropic\"}"));
        assertEquals(
                List.of(
                        "/direction: null found, string expected",
                        "/direction: must be the constant value 'anthropicMessagesToOpenaiResponses'"),
                errors(translator, "{\"direction\": null}"));
        assertEquals(
                List.of(
                        ": property 'HTTP_MAX_CONNECTIONS' is not defined in the schema and the schema does not allow additional properties"),
                errors(
                        translator,
                        "{\"direction\": \"anthropicMessagesToOpenaiResponses\", \"HTTP_MAX_CONNECTIONS\": \"10\"}"));
    }
}
