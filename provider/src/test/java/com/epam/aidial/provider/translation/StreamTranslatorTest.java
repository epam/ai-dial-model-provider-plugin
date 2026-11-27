package com.epam.aidial.provider.translation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The state machine's corners that a stream of events written by hand does not reach. */
class StreamTranslatorTest {

    private static final String CREATED = "{\"type\":\"response.created\",\"response\":{\"id\":\"r\"}}";
    private static final String COMPLETED = "{\"type\":\"response.completed\",\"response\":{\"id\":\"r\"}}";

    private final StreamTranslator translator = new StreamTranslator("m", new ToolNames());

    private static String types(List<String> events) {
        return events.stream()
                .map(event -> event.lines().findFirst().orElse("").substring("event: ".length()))
                .toList()
                .toString();
    }

    @Test
    void argumentsOfCallThatWasNeverOpenedAreDropped() {
        translator.onData(CREATED);
        List<String> events = translator.onData(
                "{\"type\":\"response.function_call_arguments.delta\",\"output_index\":0,\"delta\":\"{}\"}");
        assertEquals(List.of(), events);
    }

    @Test
    void heartbeatBeforeAnythingIsPingAndStartsNothing() {
        assertEquals("[ping]", types(translator.onHeartbeat()));
        assertEquals("[message_start]", types(translator.onData(CREATED)));
    }

    @Test
    void nothingIsSaidAfterTheEnd() {
        translator.onData(CREATED);
        assertEquals(
                "[content_block_start, content_block_stop, message_delta, message_stop]",
                types(translator.onData(COMPLETED)));
        assertEquals(List.of(), translator.onHeartbeat());
        assertEquals(List.of(), translator.onData(CREATED));
        assertEquals(List.of(), translator.onEnd());
        assertEquals(List.of(), translator.onFailure(new IllegalStateException("late")));
    }

    @Test
    void messageIdIsTheResponsesEvenWhenTheStreamStartedLazily() {
        List<String> events = translator.onData(
                "{\"type\":\"response.output_text.delta\",\"output_index\":0,\"content_index\":0,\"delta\":\"x\"}");
        assertTrue(events.getFirst().contains("\"type\":\"message_start\""));
        assertTrue(events.getFirst().contains("\"id\":\""), "a generated id: " + events.getFirst());
    }

    @Test
    void endWithoutFinalEventIsOneError() {
        translator.onData(CREATED);
        List<String> events = translator.onEnd();
        assertEquals("[error]", types(events));
        assertTrue(events.getFirst().contains("The Responses API stream ended before the response completed"));
        assertEquals(List.of(), translator.onEnd(), "said once");
    }
}
