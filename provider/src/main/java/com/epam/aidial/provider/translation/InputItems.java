package com.epam.aidial.provider.translation;

import com.anthropic.models.beta.messages.BetaContentBlockParam;
import com.anthropic.models.beta.messages.BetaMessageContentParam;
import com.anthropic.models.beta.messages.BetaMessageParam;
import com.anthropic.models.beta.messages.BetaSystemMessageParam;
import com.anthropic.models.beta.messages.BetaToolResultBlockParam;
import com.anthropic.models.beta.messages.BetaToolUseBlockParam;
import com.epam.aidial.provider.api.InvalidRequestException;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.ResponseFunctionCallOutputItem;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseInputContent;
import com.openai.models.responses.ResponseInputFile;
import com.openai.models.responses.ResponseInputFileContent;
import com.openai.models.responses.ResponseInputImage;
import com.openai.models.responses.ResponseInputImageContent;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseInputTextContent;
import com.openai.models.responses.ResponseReasoningItem;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code input} items of the Responses API that the messages of a Messages API request become. A turn's blocks keep
 * the order the Responses API needs: a function call follows the reasoning that made it, and an output comes before the
 * user text that follows the call it answers.
 */
final class InputItems {

    private static final Logger log = LoggerFactory.getLogger(InputItems.class);

    private final ContentParts parts;
    private final ToolNames names;
    private final Dropped dropped;

    InputItems(ContentParts parts, ToolNames names, Dropped dropped) {
        this.parts = parts;
        this.names = names;
        this.dropped = dropped;
    }

    /**
     * The input items of the messages, and where each block of the messages went (see {@link CachePaths}). A message
     * that is not visible (see {@link #visible}) has its blocks, and none of them went anywhere.
     */
    Result of(List<BetaMessageParam> messages) {
        List<BetaMessageParam> visible = visible(messages);
        List<ResponseInputItem> items = new ArrayList<>();
        List<int[]> itemOfBlock = new ArrayList<>();
        for (BetaMessageParam message : messages) {
            Turn turn = isVisible(visible, message) ? turn(message) : Turn.hidden(blockCount(message));
            int base = items.size();
            items.addAll(turn.items);
            int[] blocks = new int[turn.itemOfBlock.size()];
            for (int k = 0; k < blocks.length; k++) {
                ResponseInputItem item = turn.itemOfBlock.get(k);
                blocks[k] = item == null ? -1 : base + indexOfIdentity(turn.items, item);
            }
            itemOfBlock.add(blocks);
        }
        return new Result(items, itemOfBlock);
    }

    /** The items, and for each message the item each of its blocks went into ({@code -1}: none). */
    record Result(List<ResponseInputItem> items, List<int[]> itemOfBlock) {}

    private Turn turn(BetaMessageParam message) {
        return switch (message.role().value()) {
            case USER -> userTurn(message.content().orElse(null));
            case ASSISTANT -> assistantTurn(message.content().orElse(null));
            case SYSTEM -> systemTurn(message.system());
            case _UNKNOWN ->
                throw new InvalidRequestException(
                        "Unsupported message role: " + message.role().asString());
        };
    }

    /** What the Messages body holds of a message to Core, which counts a string as the one block of an array. */
    private static int blockCount(BetaMessageParam message) {
        if (message.role().value() == BetaMessageParam.Role.Value.SYSTEM) {
            return message.system()
                    .flatMap(system -> system.content())
                    .map(content -> content.blocks().map(List::size).orElse(1))
                    .orElse(0);
        }
        return message.content()
                .map(content -> content.string().isPresent()
                        ? 1
                        : content.contentBlockParams().map(List::size).orElse(0))
                .orElse(0);
    }

    private static boolean isVisible(List<BetaMessageParam> visible, BetaMessageParam message) {
        return visible.stream().anyMatch(candidate -> candidate == message);
    }

    private static int indexOfIdentity(List<ResponseInputItem> items, ResponseInputItem item) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i) == item) {
                return i;
            }
        }
        throw new IllegalStateException("an item of a block is not an item of its turn");
    }

    /** The items of one turn, in the order they are sent, and the item each of its blocks went into (null: none). */
    private static final class Turn {

        final List<ResponseInputItem> items = new ArrayList<>();
        final List<ResponseInputItem> itemOfBlock = new ArrayList<>();

        static Turn hidden(int blocks) {
            Turn turn = new Turn();
            for (int k = 0; k < blocks; k++) {
                turn.itemOfBlock.add(null);
            }
            return turn;
        }

        /** Blocks that went nowhere (an empty turn's, a string's) or all into one item. */
        static Turn of(int blocks, ResponseInputItem item) {
            Turn turn = hidden(blocks);
            if (item != null) {
                turn.items.add(item);
                for (int k = 0; k < blocks; k++) {
                    turn.itemOfBlock.set(k, item);
                }
            }
            return turn;
        }
    }

    /**
     * The messages that still count: a system turn that clears itself at the next user message is dropped once a user
     * message follows it, and stays while none does.
     */
    static List<BetaMessageParam> visible(List<BetaMessageParam> messages) {
        List<BetaMessageParam> visible = new ArrayList<>();
        boolean laterUser = false;
        for (int i = messages.size() - 1; i >= 0; i--) {
            BetaMessageParam message = messages.get(i);
            if (message.role().value() == BetaMessageParam.Role.Value.USER) {
                laterUser = true;
            } else if (laterUser && clearsAtNextUserMessage(message)) {
                continue;
            }
            visible.add(message);
        }
        Collections.reverse(visible);
        return visible;
    }

    private static boolean clearsAtNextUserMessage(BetaMessageParam message) {
        return message.system()
                .flatMap(system -> system.clearAt())
                .map(clearAt -> clearAt.value() == BetaSystemMessageParam.ClearAt.Value.NEXT_USER_MESSAGE)
                .orElse(false);
    }

    private Turn userTurn(BetaMessageContentParam content) {
        if (content == null) {
            return Turn.hidden(0);
        }
        Optional<String> string = content.string();
        if (string.isPresent()) {
            return Turn.of(
                    1,
                    string.get().isEmpty()
                            ? null
                            : message(EasyInputMessage.Role.USER, List.of(ContentParts.text(string.get()))));
        }
        // The outputs answer the calls of the previous turn, so they come first
        List<BetaContentBlockParam> blocks = content.contentBlockParams().orElse(List.of());
        Turn turn = Turn.hidden(blocks.size());
        List<ResponseInputItem> outputs = new ArrayList<>();
        List<ResponseInputContent> userParts = new ArrayList<>();
        List<Integer> userBlocks = new ArrayList<>();
        for (int k = 0; k < blocks.size(); k++) {
            BetaContentBlockParam block = blocks.get(k);
            if (block.isToolResult()) {
                ResponseInputItem output = functionCallOutput(block.asToolResult());
                outputs.add(output);
                turn.itemOfBlock.set(k, output);
            } else {
                List<ResponseInputContent> blockParts = parts.of(block);
                userParts.addAll(blockParts);
                if (!blockParts.isEmpty()) {
                    userBlocks.add(k);
                }
            }
        }
        turn.items.addAll(outputs);
        if (!userParts.isEmpty()) {
            ResponseInputItem user = message(EasyInputMessage.Role.USER, userParts);
            turn.items.add(user);
            userBlocks.forEach(k -> turn.itemOfBlock.set(k, user));
        }
        return turn;
    }

    private Turn assistantTurn(BetaMessageContentParam content) {
        if (content == null) {
            return Turn.hidden(0);
        }
        Optional<String> string = content.string();
        if (string.isPresent()) {
            return Turn.of(1, string.get().isEmpty() ? null : message(EasyInputMessage.Role.ASSISTANT, string.get()));
        }
        // A function call must follow the reasoning that made it, so the blocks keep their order, the adjacent texts
        // joined into one message
        List<BetaContentBlockParam> blocks = content.contentBlockParams().orElse(List.of());
        Turn turn = Turn.hidden(blocks.size());
        List<ResponseInputItem> items = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        List<Integer> textBlocks = new ArrayList<>();
        for (int k = 0; k < blocks.size(); k++) {
            BetaContentBlockParam block = blocks.get(k);
            ResponseInputItem item = null;
            switch (block.type().value()) {
                case TEXT -> {
                    if (!block.asText().text().isEmpty()) {
                        texts.add(block.asText().text());
                        textBlocks.add(k);
                    }
                }
                case COMPACTION -> {
                    Optional<String> text = block.asCompaction().content().filter(t -> !t.isEmpty());
                    text.ifPresent(texts::add);
                    if (text.isPresent()) {
                        textBlocks.add(k);
                    }
                }
                case TOOL_USE -> item = functionCall(block.asToolUse());
                case THINKING ->
                    item = reasoning(
                            block.asThinking().signature(), block.asThinking().thinking());
                case REDACTED_THINKING ->
                    item = reasoning(block.asRedactedThinking().data(), "");
                default ->
                    dropped.add("assistant content block "
                            + AnthropicJson.typeName(block.type()._value()));
            }
            if (item != null) {
                flush(texts, textBlocks, items, turn);
                items.add(item);
                turn.itemOfBlock.set(k, item);
            }
        }
        flush(texts, textBlocks, items, turn);
        turn.items.addAll(items);
        return turn;
    }

    /**
     * The reasoning item that a thinking block of an earlier answer of this translator carries; none for any other
     * block, a Claude one included: the signature is not a reasoning item the Responses API can use.
     */
    private static ResponseInputItem reasoning(String signature, String text) {
        ReasoningWrapper wrapper = ReasoningWrapper.decode(signature).orElse(null);
        if (wrapper == null) {
            log.debug("Dropping a thinking block: its signature isn't a reasoning item of the Responses API");
            return null;
        }
        ResponseReasoningItem.Builder item = ResponseReasoningItem.builder()
                .id(wrapper.id())
                .encryptedContent(wrapper.encryptedContent())
                .summary(List.of());
        if (!text.isEmpty()) {
            item.addSummary(ResponseReasoningItem.Summary.builder().text(text).build());
        }
        return ResponseInputItem.ofReasoning(item.build());
    }

    private static void flush(List<String> texts, List<Integer> textBlocks, List<ResponseInputItem> items, Turn turn) {
        if (!texts.isEmpty()) {
            ResponseInputItem message = message(EasyInputMessage.Role.ASSISTANT, String.join("\n", texts));
            items.add(message);
            textBlocks.forEach(k -> turn.itemOfBlock.set(k, message));
            texts.clear();
            textBlocks.clear();
        }
    }

    private ResponseInputItem functionCall(BetaToolUseBlockParam block) {
        return ResponseInputItem.ofFunctionCall(ResponseFunctionToolCall.builder()
                .callId(block.id())
                .name(names.toUpstream(block.name()))
                .arguments(block._input().isMissing() ? "{}" : AnthropicJson.write(block.input()))
                .build());
    }

    private ResponseInputItem functionCallOutput(BetaToolResultBlockParam block) {
        List<ResponseInputContent> results =
                block.content().map(parts::toolResult).orElse(List.of());
        boolean isError = block.isError().orElse(false);
        ResponseInputItem.FunctionCallOutput.Builder item =
                ResponseInputItem.FunctionCallOutput.builder().callId(block.toolUseId());
        if (results.stream().allMatch(ResponseInputContent::isInputText)) {
            String text =
                    results.stream().map(part -> part.asInputText().text()).collect(Collectors.joining("\n"));
            item.output(isError ? "Error: " + text : text);
        } else {
            // Images and documents stay in the output of the call they answer
            List<ResponseInputContent> output = new ArrayList<>();
            if (isError) {
                output.add(ContentParts.text("Error:"));
            }
            output.addAll(results);
            item.outputOfResponseFunctionCallOutputItemList(
                    output.stream().map(InputItems::outputItem).toList());
        }
        return ResponseInputItem.ofFunctionCallOutput(item.build());
    }

    /** A part of a message as the part of a function's output, which is a model of its own. */
    private static ResponseFunctionCallOutputItem outputItem(ResponseInputContent part) {
        if (part.isInputText()) {
            return ResponseFunctionCallOutputItem.ofInputText(ResponseInputTextContent.builder()
                    .text(part.asInputText().text())
                    .build());
        }
        if (part.isInputImage()) {
            ResponseInputImage image = part.asInputImage();
            ResponseInputImageContent.Builder content =
                    ResponseInputImageContent.builder().detail(ResponseInputImageContent.Detail.AUTO);
            image.imageUrl().ifPresent(content::imageUrl);
            return ResponseFunctionCallOutputItem.ofInputImage(content.build());
        }
        ResponseInputFile file = part.asInputFile();
        ResponseInputFileContent.Builder content = ResponseInputFileContent.builder();
        file.filename().ifPresent(content::filename);
        file.fileData().ifPresent(content::fileData);
        file.fileUrl().ifPresent(content::fileUrl);
        return ResponseFunctionCallOutputItem.ofInputFile(content.build());
    }

    private Turn systemTurn(Optional<BetaSystemMessageParam> system) {
        Optional<BetaSystemMessageParam.Content> content = system.flatMap(s -> s.content());
        if (content.isEmpty()) {
            return Turn.hidden(0);
        }
        return Turn.of(
                content.get().blocks().map(List::size).orElse(1),
                systemMessage(content.get()).orElse(null));
    }

    private Optional<ResponseInputItem> systemMessage(BetaSystemMessageParam.Content content) {
        List<ResponseInputContent> systemParts = systemParts(content);
        return systemParts.isEmpty()
                ? Optional.empty()
                : Optional.of(message(EasyInputMessage.Role.SYSTEM, systemParts));
    }

    private List<ResponseInputContent> systemParts(BetaSystemMessageParam.Content content) {
        List<ResponseInputContent> systemParts = new ArrayList<>();
        content.string().filter(text -> !text.isEmpty()).ifPresent(text -> systemParts.add(ContentParts.text(text)));
        for (BetaSystemMessageParam.Content.SystemMessageContentBlock block :
                content.blocks().orElse(List.of())) {
            systemParts.addAll(
                    switch (block.type().value()) {
                        case TEXT ->
                            block.asText().text().isEmpty()
                                    ? List.of()
                                    : List.of(ContentParts.text(block.asText().text()));
                        case TOOL_ADDITION, TOOL_REMOVAL, _UNKNOWN -> droppedSystemBlock(block);
                    });
        }
        return systemParts;
    }

    private List<ResponseInputContent> droppedSystemBlock(
            BetaSystemMessageParam.Content.SystemMessageContentBlock block) {
        dropped.add("system content block " + typeName(block));
        return List.of();
    }

    private static String typeName(BetaSystemMessageParam.Content.SystemMessageContentBlock block) {
        return AnthropicJson.typeName(block.type()._value());
    }

    private static ResponseInputItem message(EasyInputMessage.Role role, List<ResponseInputContent> content) {
        return ResponseInputItem.ofEasyInputMessage(EasyInputMessage.builder()
                .role(role)
                .contentOfResponseInputMessageContentList(content)
                .type(EasyInputMessage.Type.MESSAGE)
                .build());
    }

    private static ResponseInputItem message(EasyInputMessage.Role role, String text) {
        return ResponseInputItem.ofEasyInputMessage(EasyInputMessage.builder()
                .role(role)
                .content(text)
                .type(EasyInputMessage.Type.MESSAGE)
                .build());
    }
}
