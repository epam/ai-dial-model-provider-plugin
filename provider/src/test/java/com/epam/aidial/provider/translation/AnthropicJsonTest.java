package com.epam.aidial.provider.translation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.anthropic.errors.AnthropicInvalidDataException;
import com.anthropic.models.beta.messages.BetaContentBlockParam;
import com.anthropic.models.beta.messages.BetaToolUnion;
import com.anthropic.models.beta.messages.MessageCreateParams;
import com.epam.aidial.provider.api.InvalidRequestException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What the SDK's typed access does and does not give, as the translator relies on it (plan §2.8, research §B). */
class AnthropicJsonTest {

    private static MessageCreateParams.Body request(String json) {
        return AnthropicJson.parseRequest(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void richRequestIsTyped() {
        MessageCreateParams.Body body = request(
                """
                {"model":"m","max_tokens":4,"future_field":{"x":1},
                 "system":[{"type":"text","text":"be nice","cache_control":{"type":"ephemeral"}}],
                 "thinking":{"type":"adaptive"},
                 "output_config":{"effort":"high","format":{"type":"json_schema","schema":{"type":"object"}}},
                 "tool_choice":{"type":"tool","name":"f","disable_parallel_tool_use":true},
                 "messages":[
                   {"role":"system","content":"mid","clear_at":"next_user_message"},
                   {"role":"user","content":[{"type":"text","text":"hi"},
                     {"type":"image","source":{"type":"base64","media_type":"image/png","data":"AAA"}},
                     {"type":"tool_result","tool_use_id":"t1","is_error":true,
                      "content":[{"type":"text","text":"ok"},{"type":"tool_reference","tool_name":"x"}]}]},
                   {"role":"assistant","content":[{"type":"thinking","thinking":"hm","signature":"{}"},
                     {"type":"tool_use","id":"t1","name":"f","input":{"a":1}}]}]}""");
        assertEquals("m", body.model().asString());
        assertEquals(3, body.messages().size());
        assertTrue(body.messages().get(0).system().isPresent(), "a system turn");
        assertTrue(body.messages().get(1).user().isPresent());
        assertTrue(body.messages().get(2).assistant().isPresent());
        assertTrue(body._additionalProperties().containsKey("future_field"), "an unknown field is kept, unread");
    }

    @Test
    void streamIsAdditionalPropertyNotField() {
        assertFalse(AnthropicJson.stream(request("{\"model\":\"m\"}")));
        assertFalse(AnthropicJson.stream(request("{\"stream\":null}")));
        assertFalse(AnthropicJson.stream(request("{\"stream\":false}")));
        assertTrue(AnthropicJson.stream(request("{\"stream\":true}")));
        assertThrows(InvalidRequestException.class, () -> AnthropicJson.stream(request("{\"stream\":\"yes\"}")));
    }

    @Test
    void toolsAreToldApartByTheirKind() {
        MessageCreateParams.Body body = request(
                """
                {"tools":[{"name":"f","description":"d","input_schema":{"type":"object"}},
                          {"type":"custom","name":"g","input_schema":{"type":"object"}},
                          {"type":"web_search_20250305","name":"web_search","max_uses":3},
                          {"type":"bash_20250124","name":"bash"},
                          {"type":"brand_new_tool_2030","name":"zzz"}]}""");
        List<BetaToolUnion> tools = body.tools().orElseThrow();
        assertEquals(
                List.of(ToolKind.CUSTOM, ToolKind.CUSTOM, ToolKind.WEB_SEARCH, ToolKind.OTHER, ToolKind.OTHER),
                tools.stream().map(ToolKind::of).toList());
        assertTrue(tools.get(4).isBetaTool(), "the model itself takes the unknown tool for a custom one");
        assertEquals(
                java.util.Map.of("type", "brand_new_tool_2030", "name", "zzz"), AnthropicJson.toMap(tools.get(4)));
    }

    @Test
    void blocksTheModelHasNoVariantForAreNamedByTheirType() {
        MessageCreateParams.Body body = request(
                """
                {"messages":[{"role":"user","content":[
                  {"type":"text","text":"hi"},
                  {"type":"mid_conv_system","content":"sys"},
                  {"type":"brand_new_block","a":1},
                  {"a":1}]}]}""");
        List<BetaContentBlockParam> blocks = body.messages()
                .get(0)
                .user()
                .orElseThrow()
                .content()
                .contentBlockParams()
                .orElseThrow();
        assertEquals(
                List.of("text", "mid_conv_system", "brand_new_block", "(none)"),
                blocks.stream()
                        .map(b -> AnthropicJson.typeName(b.type()._value()))
                        .toList());
        assertEquals(
                "sys",
                AnthropicJson.midConvSystem(blocks.get(1)._json())
                        .orElseThrow()
                        .string()
                        .orElseThrow());
    }

    @Test
    void effortKeepsStringOfValueEnumLacks() {
        assertEquals(java.util.Optional.empty(), AnthropicJson.effort(request("{\"model\":\"m\"}")));
        assertEquals(
                "xhigh",
                AnthropicJson.effort(request("{\"output_config\":{\"effort\":\"xhigh\"}}"))
                        .orElseThrow());
        assertEquals(
                "ultra",
                AnthropicJson.effort(request("{\"output_config\":{\"effort\":\"ultra\"}}"))
                        .orElseThrow());
    }

    @Test
    void parsingIsLazyAndGettersFailOnWhatTheyRead() {
        MessageCreateParams.Body body = request("{\"messages\":[{\"role\":\"user\",\"content\":5}]}");
        assertThrows(AnthropicInvalidDataException.class, body::model);
        assertEquals(1, body.messages().size(), "what was not read is not checked");
    }

    @Test
    void bodyThatIsNotObjectIsInvalidRequest() {
        for (String json : new String[] {"", "{", "[]", "5", "{\"a\":1}x"}) {
            InvalidRequestException e = assertThrows(InvalidRequestException.class, () -> request(json), json);
            assertTrue(e.getMessage().startsWith("The request body is not valid JSON: "), e.getMessage());
        }
    }

    @Test
    void streamEventIsWrittenInApiSpelling() {
        assertEquals(
                "{\"index\":0,\"type\":\"content_block_stop\"}",
                AnthropicJson.write(com.anthropic.models.messages.RawMessageStreamEvent.ofContentBlockStop(
                        com.anthropic.models.messages.RawContentBlockStopEvent.builder()
                                .index(0)
                                .build())));
    }
}
