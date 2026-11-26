package com.epam.aidial.provider.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.epam.aidial.core.plugin.PluginProvider.Problem;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;

class TranslatorPluginProviderTest {

    private final TranslatorPluginProvider provider = new TranslatorPluginProvider();

    private static ObjectNode entry() {
        return JsonNodeFactory.instance.objectNode();
    }

    @Test
    void typeIsTranslator() {
        assertEquals("translator", provider.type());
    }

    @Test
    void entryOfKnownTranslatorIsValid() {
        assertEquals(List.of(), provider.validate(entry().put("translator", "openaiResponses")));
    }

    @Test
    void entryOfAnUnknownTranslatorNamesTheKnownOnes() {
        Problem expected = new Problem("translator", "must be one of openaiResponses");
        assertEquals(List.of(expected), provider.validate(entry().put("translator", "nope")));
        assertEquals(List.of(expected), provider.validate(entry().put("translator", 1)));
        assertEquals(List.of(expected), provider.validate(entry().putNull("translator")));
        assertEquals(List.of(expected), provider.validate(entry()));
    }

    @Test
    void settingThatIsNotAnObjectsOrTextIsRefused() {
        ObjectNode array = entry();
        array.putArray("translator").add("openaiResponses");
        assertEquals(List.of(new Problem("translator", "must be text")), provider.validate(array));
    }

    @Test
    void otherSettingIsRefused() {
        assertEquals(
                List.of(new Problem("HTTP_MAX_CONNECTIONS", "is not a setting of a translator")),
                provider.validate(entry().put("translator", "openaiResponses").put("HTTP_MAX_CONNECTIONS", "10")));
    }
}
