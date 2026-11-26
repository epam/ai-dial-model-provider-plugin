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

    private static ObjectNode translator() {
        return entry().put("direction", "anthropicMessagesToOpenaiResponses");
    }

    @Test
    void typeIsTranslator() {
        assertEquals("translator", provider.type());
    }

    @Test
    void messagesToResponsesIsTheDirectionThereIs() {
        assertEquals(List.of(), provider.validate(translator()));
    }

    @Test
    void anotherDirectionIsRefusedAndNamesTheOneThereIs() {
        Problem expected = new Problem("direction", "must be one of anthropicMessagesToOpenaiResponses");
        assertEquals(List.of(expected), provider.validate(entry().put("direction", "openaiResponsesToAnthropic")));
        assertEquals(List.of(expected), provider.validate(entry().put("direction", 1)));
        ObjectNode array = entry();
        array.putArray("direction").add("anthropicMessagesToOpenaiResponses");
        assertEquals(List.of(expected), provider.validate(array));
    }

    @Test
    void missingOrNullDirectionIsRefused() {
        Problem expected = new Problem("direction", "is required");
        assertEquals(List.of(expected), provider.validate(entry()));
        assertEquals(List.of(expected), provider.validate(entry().putNull("direction")));
    }

    @Test
    void otherSettingIsRefused() {
        assertEquals(
                List.of(new Problem("HTTP_MAX_CONNECTIONS", "is not a setting of a translator")),
                provider.validate(translator().put("HTTP_MAX_CONNECTIONS", "10")));
    }
}
