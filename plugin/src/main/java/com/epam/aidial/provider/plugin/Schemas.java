package com.epam.aidial.provider.plugin;

import com.epam.aidial.provider.core.Settings;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.victools.jsonschema.generator.Option;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import com.github.victools.jsonschema.module.jackson.JacksonOption;
import java.util.Arrays;

/**
 * The JSON Schema (draft-07) of a settings model, derived from its Jackson annotations: each setting has the type of
 * its Java type, and no setting but the model's own is accepted.
 */
final class Schemas {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Schemas() {}

    static ObjectNode of(Class<?> settings) {
        var config = new SchemaGeneratorConfigBuilder(SchemaVersion.DRAFT_7, OptionPreset.PLAIN_JSON)
                .with(new JacksonModule(
                        JacksonOption.RESPECT_JSONPROPERTY_REQUIRED,
                        JacksonOption.FLATTENED_ENUMS_FROM_JSONPROPERTY,
                        JacksonOption.RESPECT_JSONPROPERTY_ORDER))
                .with(Option.INLINE_ALL_SCHEMAS)
                .with(Option.MAP_VALUES_AS_ADDITIONAL_PROPERTIES)
                .with(Option.FORBIDDEN_ADDITIONAL_PROPERTIES_BY_DEFAULT);
        return new SchemaGenerator(config.build()).generateSchema(settings);
    }

    /** The schema of the service's {@link Settings}, and of the adapter an entry serves, which is required. */
    static ObjectNode modelProvider() {
        ObjectNode schema = of(Settings.class);
        ObjectNode adapter = MAPPER.createObjectNode().put("type", "string");
        Arrays.stream(Adapter.values()).map(Adapter::value).forEach(adapter.putArray("enum")::add);
        adapter.put(
                "description",
                "The cloud API the entry serves: openai (Azure OpenAI and OpenAI), vertexai (Anthropic on Vertex AI) or bedrock (Anthropic on Amazon Bedrock).");
        ObjectNode properties = MAPPER.createObjectNode();
        properties.set(ModelProviderSettings.ADAPTER, adapter);
        properties.setAll((ObjectNode) schema.get("properties"));
        schema.set("properties", properties);
        schema.putArray("required").add(ModelProviderSettings.ADAPTER);
        return schema;
    }
}
