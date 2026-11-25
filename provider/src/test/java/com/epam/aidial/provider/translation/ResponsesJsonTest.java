package com.epam.aidial.provider.translation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.errors.OpenAIInvalidDataException;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseIncludable;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseStreamEvent;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What the SDK's typed access does and does not give for the Responses API. */
class ResponsesJsonTest {

    private static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void sparseResponseParsesAndUnknownItemsAreRaw() {
        Response response = ResponsesJson.parseResponse(
                bytes(
                        """
                {"id":"resp_1","status":"completed","output":[
                  {"type":"message","id":"m","role":"assistant","status":"completed",
                   "content":[{"type":"output_text","text":"Hello","annotations":[]}]},
                  {"type":"future_item","z":1},
                  {"type":"function_call","call_id":"c","name":"f","arguments":"{}"}],
                 "usage":{"input_tokens":10,"output_tokens":2,
                   "input_tokens_details":{"cached_tokens":4,"cache_write_tokens":1},
                   "output_tokens_details":{"reasoning_tokens":0},"total_tokens":12}}"""));
        assertEquals("resp_1", response.id());
        assertEquals(3, response.output().size());
        assertTrue(response.output().get(0).isMessage());
        assertFalse(
                response.output().get(1).isMessage() || response.output().get(1).isFunctionCall());
        assertTrue(response.output().get(1)._json().isPresent(), "an item of an unknown type has only its JSON");
        assertTrue(response.output().get(2).isFunctionCall());
        var details = response.usage().orElseThrow().inputTokensDetails();
        assertEquals(4, details.cachedTokens());
        assertEquals(1, details.cacheWriteTokens());
    }

    @Test
    void streamEventIsClassifiedByItsType() {
        ResponseStreamEvent delta = ResponsesJson.parseEvent(
                bytes(
                        """
                {"type":"response.output_text.delta","output_index":0,"content_index":0,"item_id":"i",
                 "sequence_number":1,"delta":"Hel","logprobs":[]}"""));
        assertEquals("Hel", delta.outputTextDelta().orElseThrow().delta());

        ResponseStreamEvent unknown = ResponsesJson.parseEvent(bytes("{\"type\":\"response.future.thing\",\"a\":1}"));
        assertTrue(unknown._json().isPresent(), "an unknown type has only its JSON");
        assertFalse(unknown.isOutputTextDelta());
    }

    @Test
    void mistypedEventIsClassifiedAndFailsWhereRead() {
        ResponseStreamEvent mistyped = ResponsesJson.parseEvent(
                bytes("{\"type\":\"response.output_text.delta\",\"output_index\":\"zero\",\"delta\":\"x\"}"));
        assertTrue(mistyped.isOutputTextDelta());
        assertEquals("x", mistyped.outputTextDelta().orElseThrow().delta(), "what is read and fine");
        assertThrows(
                OpenAIInvalidDataException.class,
                () -> mistyped.outputTextDelta().orElseThrow().outputIndex());
    }

    @Test
    void whatIsNotJsonIsAnUpstreamResponseException() {
        for (String json : new String[] {"", "{", "<html>", "{\"id\":\"r\"}x"}) {
            assertThrows(UpstreamResponseException.class, () -> ResponsesJson.parseResponse(bytes(json)), json);
            assertThrows(UpstreamResponseException.class, () -> ResponsesJson.parseEvent(bytes(json)), json);
        }
    }

    @Test
    void requestIsWrittenWithStreamFlagTheModelLacks() throws Exception {
        ResponseCreateParams.Builder request = ResponseCreateParams.builder()
                .model("gpt-5.5")
                .store(false)
                .addInclude(ResponseIncludable.REASONING_ENCRYPTED_CONTENT)
                .inputOfResponse(List.of(ResponseInputItem.ofMessage(ResponseInputItem.Message.builder()
                        .role(ResponseInputItem.Message.Role.USER)
                        .addInputTextContent("hi")
                        .build())));
        JsonNode json = new ObjectMapper().readTree(ResponsesJson.requestJson(request, true));
        assertEquals("gpt-5.5", json.path("model").asText());
        assertTrue(json.path("stream").asBoolean());
        assertFalse(json.path("store").asBoolean(true));
        assertEquals("reasoning.encrypted_content", json.path("include").path(0).asText());
        assertEquals("user", json.path("input").path(0).path("role").asText());
        assertEquals(
                "hi",
                json.path("input").path(0).path("content").path(0).path("text").asText());
        assertEquals(
                "input_text",
                json.path("input").path(0).path("content").path(0).path("type").asText());
    }
}
