package com.epam.aidial.provider.plugin;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.victools.jsonschema.generator.Option;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import com.github.victools.jsonschema.module.jackson.JacksonOption;

/**
 * The JSON Schema (draft-07) of a settings model, derived from its Jackson annotations. It is the strict one: reading
 * an entry is lax.
 */
final class Schemas {

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
}
