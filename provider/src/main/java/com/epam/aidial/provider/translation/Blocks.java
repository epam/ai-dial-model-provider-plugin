package com.epam.aidial.provider.translation;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.DirectCaller;
import com.anthropic.models.messages.RawContentBlockStartEvent;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.ThinkingBlock;
import com.anthropic.models.messages.ToolUseBlock;
import java.util.Optional;

/**
 * The content blocks of a Messages API answer, as the Anthropic SDK's models: the one source of the blocks of a message
 * and of the blocks a stream opens.
 */
final class Blocks {

    private static final DirectCaller DIRECT = DirectCaller.builder().build();

    private Blocks() {}

    /** What called a tool: the model itself, as for every tool of the request. */
    static DirectCaller direct() {
        return DIRECT;
    }

    static ContentBlock text(String text) {
        return ContentBlock.ofText(
                TextBlock.builder().text(text).citations(Optional.empty()).build());
    }

    static ContentBlock thinking(String thinking, String signature) {
        return ContentBlock.ofThinking(
                ThinkingBlock.builder().thinking(thinking).signature(signature).build());
    }

    static ContentBlock redactedThinking(String data) {
        return ContentBlock.ofRedactedThinking(data);
    }

    static ContentBlock toolUse(String id, String name, JsonValue input) {
        return ContentBlock.ofToolUse(ToolUseBlock.builder()
                .id(id)
                .caller(DIRECT)
                .name(name)
                .input(input)
                .build());
    }

    /**
     * A block of a message as the block a stream opens with: the two are unions of the same variants, and the stream's
     * has no conversion from the message's.
     *
     * @throws IllegalArgumentException for a block of a type the translator never writes
     */
    static RawContentBlockStartEvent.ContentBlock opening(ContentBlock block) {
        return block.text()
                .map(RawContentBlockStartEvent.ContentBlock::ofText)
                .or(() -> block.thinking().map(RawContentBlockStartEvent.ContentBlock::ofThinking))
                .or(() -> block.redactedThinking().map(RawContentBlockStartEvent.ContentBlock::ofRedactedThinking))
                .or(() -> block.toolUse().map(RawContentBlockStartEvent.ContentBlock::ofToolUse))
                .or(() -> block.serverToolUse().map(RawContentBlockStartEvent.ContentBlock::ofServerToolUse))
                .or(() ->
                        block.webSearchToolResult().map(RawContentBlockStartEvent.ContentBlock::ofWebSearchToolResult))
                .orElseThrow(() -> new IllegalArgumentException("not a block a stream opens: " + block));
    }
}
