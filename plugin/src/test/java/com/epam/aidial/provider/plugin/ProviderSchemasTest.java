package com.epam.aidial.provider.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.epam.aidial.core.plugin.PluginProvider;
import com.epam.aidial.core.plugin.PluginTarget;
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

    private final PluginProvider modelProvider = new ModelProviderPluginProvider();
    private final PluginProvider translator = new TranslatorPluginProvider();

    private static List<String> errors(PluginProvider provider, String settings) throws Exception {
        Schema schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_7)
                .getSchema(MAPPER.writeValueAsString(provider.settingsSchema()));
        return schema.validate(MAPPER.readTree(settings)).stream()
                .map(error -> error.getInstanceLocation() + ": " + error.getMessage())
                .toList();
    }

    @Test
    void providersDescribeThemselves() {
        assertEquals("model-provider", modelProvider.id());
        assertEquals(Set.of(PluginTarget.MODEL), modelProvider.targets());
        assertEquals("translator", translator.id());
        assertEquals(Set.of(PluginTarget.TRANSLATOR), translator.targets());
    }

    @Test
    void schemasAreSelfContainedDraft07() {
        for (PluginProvider provider : List.of(modelProvider, translator)) {
            ObjectNode schema = provider.settingsSchema();
            assertEquals(
                    "http://json-schema.org/draft-07/schema#",
                    schema.get("$schema").asText());
            assertFalse(schema.toString().contains("$ref"), provider.id());
        }
    }

    @Test
    void everyVariableIsDescribed() {
        JsonNode properties = modelProvider.settingsSchema().get("properties");
        assertEquals(33, properties.size());
        properties.forEach(property -> assertTrue(property.hasNonNull("description"), property.toString()));
    }

    @Test
    void adaptersAreTheOnesThereAre() {
        JsonNode adapter = modelProvider.settingsSchema().get("properties").get("adapter");
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
    void modelProviderVariablesAreTextNumbersBooleansObjectsOrNull() throws Exception {
        String variables =
                """
                {"adapter": "bedrock", "HTTP_MAX_CONNECTIONS": 10, "HTTP_POOL_TIMEOUT": "10", "NO_PROXY": null,
                 "AWS_SESSION_TAGS": {"team": "UserInfo.team"}, "http_proxy": "http://proxy", "SOMETHING_NEW": true}
                """;
        assertEquals(List.of(), errors(modelProvider, variables));
        assertEquals(
                List.of("/HTTP_MAX_CONNECTIONS: array found, [string, number, boolean, object, null] expected"),
                errors(modelProvider, "{\"adapter\": \"openai\", \"HTTP_MAX_CONNECTIONS\": [1]}"));
        assertEquals(
                List.of("/SOMETHING_NEW: array found, [string, number, boolean, object, null] expected"),
                errors(modelProvider, "{\"adapter\": \"openai\", \"SOMETHING_NEW\": [1]}"));
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
