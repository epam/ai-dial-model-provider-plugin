package com.epam.aidial.provider.plugin;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.victools.jsonschema.generator.CustomDefinition;
import com.github.victools.jsonschema.generator.Option;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import com.github.victools.jsonschema.module.jackson.JacksonOption;
import java.lang.reflect.Type;

/** The JSON Schema (draft-07) of a settings model, derived from its Jackson annotations. */
final class Schemas {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Schemas() {}

    /**
     * @param additionalProperties what the settings besides the model's own are: a type for the values of any name, or
     *     {@link Void} for none
     */
    static ObjectNode of(Class<?> settings, Type additionalProperties) {
        var config = new SchemaGeneratorConfigBuilder(SchemaVersion.DRAFT_7, OptionPreset.PLAIN_JSON)
                .with(new JacksonModule(
                        JacksonOption.RESPECT_JSONPROPERTY_REQUIRED,
                        JacksonOption.FLATTENED_ENUMS_FROM_JSONPROPERTY,
                        JacksonOption.RESPECT_JSONPROPERTY_ORDER))
                .with(Option.INLINE_ALL_SCHEMAS);
        config.forTypesInGeneral()
                .withCustomDefinitionProvider((type, context) -> type.getErasedType() == Variable.class
                        ? new CustomDefinition(
                                variable(),
                                CustomDefinition.DefinitionType.INLINE,
                                CustomDefinition.AttributeInclusion.YES)
                        : null)
                .withAdditionalPropertiesResolver(
                        scope -> scope.getType().getErasedType() == settings ? additionalProperties : null);
        // The map the other variables are collected in is no property of its own
        config.forFields()
                .withIgnoreCheck(field -> field.getAnnotationConsideringFieldAndGetter(JsonAnySetter.class) != null);
        return new SchemaGenerator(config.build()).generateSchema(settings);
    }

    /** What {@link Variable} reads: null unsets the variable. */
    private static ObjectNode variable() {
        var schema = MAPPER.createObjectNode();
        schema.putArray("type")
                .add("string")
                .add("number")
                .add("boolean")
                .add("object")
                .add("null");
        return schema;
    }
}
