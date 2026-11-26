package com.epam.aidial.provider.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.epam.aidial.core.plugin.PluginProvider.Problem;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PluginSettingsTest {

    private static ObjectNode entry() {
        return JsonNodeFactory.instance.objectNode();
    }

    private static List<Problem> validate(ObjectNode entry) {
        return new ModelProviderPluginProvider(Map.of()).validate(entry);
    }

    @Test
    void adapterIsTheNameTheEntryServes() {
        PluginSettings adapter = PluginSettings.of(entry().put("adapter", "openai"));
        assertTrue(adapter.hasAdapter());
        assertEquals("openai", adapter.adapter());
    }

    @Test
    void settingThatIsNullIsNone() {
        assertFalse(PluginSettings.of(entry().putNull("adapter")).hasAdapter());
    }

    @Test
    void settingThatIsNotTextIsThereAndNamesNothing() {
        PluginSettings settings = PluginSettings.of(entry().put("adapter", 1));
        assertTrue(settings.hasAdapter());
        assertEquals("", settings.adapter());
    }

    @Test
    void variablesAreTheEnvironmentThenTheEntryWithoutTheKeysItReadsItself() {
        ObjectNode entry =
                entry().put("adapter", "openai").put("A", "entry").putNull("B").put("N", 10);
        entry.put("T", true).putObject("O").put("x", "y");
        entry.putArray("L").add(1);
        assertEquals(
                Map.of("A", "entry", "C", "env", "N", "10", "T", "true", "O", "{\"x\":\"y\"}", "L", "[1]"),
                PluginSettings.of(entry).variables(Map.of("A", "env", "B", "env", "C", "env")));
    }

    @Test
    void entryOfAnUnknownAdapterNamesTheKnownOnes() {
        assertEquals(
                List.of(new Problem("adapter", "must be one of bedrock, openai, vertexai")),
                validate(entry().put("adapter", "nope")));
        assertEquals(
                List.of(new Problem("adapter", "must be one of bedrock, openai, vertexai")),
                validate(entry().put("adapter", 1)));
    }

    @Test
    void entryWithoutAnAdapterIsRefusedAndNamesThem() {
        assertEquals(
                List.of(new Problem("adapter", "an entry needs an adapter (one of bedrock, openai, vertexai)")),
                validate(entry()));
        assertEquals(
                List.of(new Problem("adapter", "an entry needs an adapter (one of bedrock, openai, vertexai)")),
                validate(entry().put("translator", "openaiResponses")));
    }
}
