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

/** How an entry's settings are read: what it is (an adapter or a translator), and what the rest of it sets. */
class PluginSettingsTest {

    private static ObjectNode entry() {
        return JsonNodeFactory.instance.objectNode();
    }

    private static List<Problem> validate(ObjectNode entry) {
        return new ModelProviderPluginProvider(Map.of()).validate(entry);
    }

    @Test
    void entryIsAdapterOrTranslator() {
        PluginSettings adapter = PluginSettings.of(entry().put("adapter", "openai"));
        assertTrue(adapter.hasAdapter());
        assertFalse(adapter.hasTranslator());
        assertEquals("openai", adapter.adapter());

        PluginSettings translator = PluginSettings.of(entry().put("translator", "openaiResponses"));
        assertFalse(translator.hasAdapter());
        assertTrue(translator.hasTranslator());
        assertEquals("openaiResponses", translator.translator());
    }

    @Test
    void settingThatIsNullIsNone() {
        PluginSettings settings = PluginSettings.of(entry().putNull("adapter").putNull("translator"));
        assertFalse(settings.hasAdapter());
        assertFalse(settings.hasTranslator());
    }

    @Test
    void settingThatIsNotTextIsThereAndNamesNothing() {
        PluginSettings settings = PluginSettings.of(entry().put("adapter", 1).put("translator", true));
        assertTrue(settings.hasAdapter());
        assertEquals("", settings.adapter());
        assertTrue(settings.hasTranslator());
        assertEquals("", settings.translator());
    }

    @Test
    void variablesAreTheEnvironmentThenTheEntryWithoutTheKeysItReadsItself() {
        ObjectNode entry = entry().put("translator", "openaiResponses")
                .put("A", "entry")
                .putNull("B")
                .put("N", 10);
        entry.put("T", true).putObject("O").put("x", "y");
        entry.putArray("L").add(1);
        assertEquals(
                Map.of("A", "entry", "C", "env", "N", "10", "T", "true", "O", "{\"x\":\"y\"}", "L", "[1]"),
                PluginSettings.of(entry).variables(Map.of("A", "env", "B", "env", "C", "env")));
    }

    @Test
    void translatorEntryIsValid() {
        assertEquals(List.of(), validate(entry().put("translator", "openaiResponses")));
    }

    @Test
    void translatorEntryOfAnUnknownTranslatorNamesTheKnownOnes() {
        assertEquals(
                List.of(new Problem("translator", "must be one of openaiResponses")),
                validate(entry().put("translator", "nope")));
        assertEquals(
                List.of(new Problem("translator", "must be one of openaiResponses")),
                validate(entry().put("translator", 1)));
    }

    @Test
    void entryWithBothAdapterAndTranslatorIsRefused() {
        assertEquals(
                List.of(new Problem("translator", "an entry has an adapter or a translator, not both")),
                validate(entry().put("adapter", "openai").put("translator", "openaiResponses")));
    }

    @Test
    void entryWithNeitherIsRefusedAndNamesBoth() {
        assertEquals(
                List.of(new Problem(
                        "adapter",
                        "an entry needs an adapter (one of bedrock, openai, vertexai) or a translator (one of"
                                + " openaiResponses)")),
                validate(entry()));
    }

    @Test
    void settingOfTranslatorEntryIsCheckedLikeAnyOther() {
        assertEquals(
                new Problem("HTTP_MAX_CONNECTIONS", "HTTP_MAX_CONNECTIONS must be an integer"),
                validate(entry().put("translator", "openaiResponses").put("HTTP_MAX_CONNECTIONS", "many"))
                        .getFirst());
    }
}
