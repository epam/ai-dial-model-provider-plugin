package com.epam.aidial.provider.translation;

import com.anthropic.core.JsonValue;
import com.anthropic.models.beta.messages.BetaContentBlockParam;
import com.anthropic.models.beta.messages.BetaMessageContentParam;
import com.anthropic.models.beta.messages.BetaMessageParam;
import com.anthropic.models.beta.messages.BetaSystemMessageParam;
import com.anthropic.models.beta.messages.BetaToolResultBlockParam;
import com.anthropic.models.beta.messages.BetaToolUseBlockParam;
import com.epam.aidial.provider.api.InvalidRequestException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private static final String MID_CONV_SYSTEM = "mid_conv_system";

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
        List<Map<String, Object>> items = new ArrayList<>();
        List<int[]> itemOfBlock = new ArrayList<>();
        for (BetaMessageParam message : messages) {
            Turn turn = isVisible(visible, message) ? turn(message) : Turn.hidden(blockCount(message));
            int base = items.size();
            items.addAll(turn.items);
            int[] blocks = new int[turn.itemOfBlock.size()];
            for (int k = 0; k < blocks.length; k++) {
                Map<String, Object> item = turn.itemOfBlock.get(k);
                blocks[k] = item == null ? -1 : base + indexOfIdentity(turn.items, item);
            }
            itemOfBlock.add(blocks);
        }
        return new Result(items, itemOfBlock);
    }

    /** The items, and for each message the item each of its blocks went into ({@code -1}: none). */
    record Result(List<Map<String, Object>> items, List<int[]> itemOfBlock) {}

    private Turn turn(BetaMessageParam message) {
        String role = message.role().asString();
        return switch (role) {
            case "user" -> userTurn(message.content().orElse(null));
            case "assistant" -> assistantTurn(message.content().orElse(null));
            case "system" -> systemTurn(message.system());
            default -> throw new InvalidRequestException("Unsupported message role: " + role);
        };
    }

    /** What the Messages body holds of a message to Core, which counts a string as the one block of an array. */
    private static int blockCount(BetaMessageParam message) {
        if (message.role().asString().equals("system")) {
            JsonValue content = message.system()
                    .flatMap(s -> s.content())
                    .flatMap(c -> c._json())
                    .orElse(null);
            return content == null
                    ? 0
                    : AnthropicJson.array(content).map(List::size).orElse(1);
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

    private static int indexOfIdentity(List<Map<String, Object>> items, Map<String, Object> item) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i) == item) {
                return i;
            }
        }
        throw new IllegalStateException("an item of a block is not an item of its turn");
    }

    /** The items of one turn, in the order they are sent, and the item each of its blocks went into (null: none). */
    private static final class Turn {

        final List<Map<String, Object>> items = new ArrayList<>();
        final List<Map<String, Object>> itemOfBlock = new ArrayList<>();

        static Turn hidden(int blocks) {
            Turn turn = new Turn();
            for (int k = 0; k < blocks; k++) {
                turn.itemOfBlock.add(null);
            }
            return turn;
        }

        /** Blocks that went nowhere (an empty turn's, a string's) or all into one item. */
        static Turn of(int blocks, Map<String, Object> item) {
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
            if (message.role().asString().equals("user")) {
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
                .map(clearAt -> clearAt.asString().equals("next_user_message"))
                .orElse(false);
    }

    private Turn userTurn(BetaMessageContentParam content) {
        if (content == null) {
            return Turn.hidden(0);
        }
        Optional<String> string = content.string();
        if (string.isPresent()) {
            return Turn.of(
                    1, string.get().isEmpty() ? null : message("user", List.of(ContentParts.text(string.get()))));
        }
        // The outputs answer the calls of the previous turn, so they come first, and the system text prefaces the
        // user text that follows it
        List<BetaContentBlockParam> blocks = content.contentBlockParams().orElse(List.of());
        Turn turn = Turn.hidden(blocks.size());
        List<Map<String, Object>> outputs = new ArrayList<>();
        List<Map<String, Object>> system = new ArrayList<>();
        List<Map<String, Object>> userParts = new ArrayList<>();
        List<Integer> userBlocks = new ArrayList<>();
        for (int k = 0; k < blocks.size(); k++) {
            BetaContentBlockParam block = blocks.get(k);
            if (block.isToolResult()) {
                Map<String, Object> output = functionCallOutput(block.asToolResult());
                outputs.add(output);
                turn.itemOfBlock.set(k, output);
            } else if (isMidConvSystem(block)) {
                List<Map<String, Object>> message = midConvSystem(block);
                system.addAll(message);
                turn.itemOfBlock.set(k, message.isEmpty() ? null : message.getFirst());
            } else {
                List<Map<String, Object>> blockParts = parts.of(block);
                userParts.addAll(blockParts);
                if (!blockParts.isEmpty()) {
                    userBlocks.add(k);
                }
            }
        }
        turn.items.addAll(outputs);
        turn.items.addAll(system);
        if (!userParts.isEmpty()) {
            Map<String, Object> user = message("user", userParts);
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
            return Turn.of(1, string.get().isEmpty() ? null : message("assistant", string.get()));
        }
        // A function call must follow the reasoning that made it, so the blocks keep their order, the adjacent texts
        // joined into one message
        List<BetaContentBlockParam> blocks = content.contentBlockParams().orElse(List.of());
        Turn turn = Turn.hidden(blocks.size());
        List<Map<String, Object>> system = new ArrayList<>();
        List<Map<String, Object>> items = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        List<Integer> textBlocks = new ArrayList<>();
        for (int k = 0; k < blocks.size(); k++) {
            BetaContentBlockParam block = blocks.get(k);
            Map<String, Object> item = null;
            if (block.isText()) {
                if (!block.asText().text().isEmpty()) {
                    texts.add(block.asText().text());
                    textBlocks.add(k);
                }
            } else if (block.isCompaction()) {
                Optional<String> text = block.asCompaction().content().filter(t -> !t.isEmpty());
                text.ifPresent(texts::add);
                if (text.isPresent()) {
                    textBlocks.add(k);
                }
            } else if (block.isToolUse()) {
                item = functionCall(block.asToolUse());
            } else if (block.isThinking()) {
                item = reasoning(
                        block.asThinking().signature(), block.asThinking().thinking());
            } else if (block.isRedactedThinking()) {
                item = reasoning(block.asRedactedThinking().data(), "");
            } else if (isMidConvSystem(block)) {
                List<Map<String, Object>> message = midConvSystem(block);
                system.addAll(message);
                turn.itemOfBlock.set(k, message.isEmpty() ? null : message.getFirst());
            } else {
                dropped.add("assistant content block " + AnthropicJson.blockType(block));
            }
            if (item != null) {
                flush(texts, textBlocks, items, turn);
                items.add(item);
                turn.itemOfBlock.set(k, item);
            }
        }
        flush(texts, textBlocks, items, turn);
        // After the turn, the system text could separate a call from its output
        turn.items.addAll(system);
        turn.items.addAll(items);
        return turn;
    }

    /**
     * The reasoning item that a thinking block of an earlier answer of this translator carries; none for any other
     * block, a Claude one included: the signature is not a reasoning item the Responses API can use.
     */
    private static Map<String, Object> reasoning(String signature, String text) {
        ReasoningWrapper wrapper = ReasoningWrapper.decode(signature).orElse(null);
        if (wrapper == null) {
            log.debug("Dropping a thinking block: its signature isn't a reasoning item of the Responses API");
            return null;
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "reasoning");
        item.put("id", wrapper.id());
        item.put("encrypted_content", wrapper.encryptedContent());
        item.put("summary", text.isEmpty() ? List.of() : List.of(summaryText(text)));
        return item;
    }

    private static Map<String, Object> summaryText(String text) {
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("type", "summary_text");
        part.put("text", text);
        return part;
    }

    private static void flush(
            List<String> texts, List<Integer> textBlocks, List<Map<String, Object>> items, Turn turn) {
        if (!texts.isEmpty()) {
            Map<String, Object> message = message("assistant", String.join("\n", texts));
            items.add(message);
            textBlocks.forEach(k -> turn.itemOfBlock.set(k, message));
            texts.clear();
            textBlocks.clear();
        }
    }

    private Map<String, Object> functionCall(BetaToolUseBlockParam block) {
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("type", "function_call");
        call.put("call_id", block.id());
        call.put("name", names.toUpstream(block.name()));
        call.put("arguments", block._input().isMissing() ? "{}" : AnthropicJson.write(block.input()));
        return call;
    }

    private Map<String, Object> functionCallOutput(BetaToolResultBlockParam block) {
        List<Map<String, Object>> results =
                block.content().map(parts::toolResult).orElse(List.of());
        boolean isError = block.isError().orElse(false);
        boolean onlyText = results.stream().allMatch(part -> part.get("type").equals("input_text"));
        Object output;
        if (onlyText) {
            String text =
                    results.stream().map(part -> (String) part.get("text")).collect(Collectors.joining("\n"));
            output = isError ? "Error: " + text : text;
        } else {
            // Images and documents stay in the output of the call they answer
            List<Map<String, Object>> parts = new ArrayList<>();
            if (isError) {
                parts.add(ContentParts.text("Error:"));
            }
            parts.addAll(results);
            output = parts;
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "function_call_output");
        item.put("call_id", block.toolUseId());
        item.put("output", output);
        return item;
    }

    private Turn systemTurn(Optional<BetaSystemMessageParam> system) {
        JsonValue content =
                system.flatMap(s -> s.content()).flatMap(c -> c._json()).orElse(null);
        if (content == null) {
            return Turn.hidden(0);
        }
        List<Map<String, Object>> message = systemMessage(content);
        return Turn.of(
                AnthropicJson.array(content).map(List::size).orElse(1), message.isEmpty() ? null : message.getFirst());
    }

    private static boolean isMidConvSystem(BetaContentBlockParam block) {
        return AnthropicJson.blockType(block).equals(MID_CONV_SYSTEM);
    }

    private List<Map<String, Object>> midConvSystem(BetaContentBlockParam block) {
        JsonValue content = AnthropicJson.object(AnthropicJson.raw(block))
                .map(object -> object.get("content"))
                .orElse(null);
        return content == null ? List.of() : systemMessage(content);
    }

    private List<Map<String, Object>> systemMessage(JsonValue content) {
        List<Map<String, Object>> systemParts = systemParts(content);
        return systemParts.isEmpty() ? List.of() : List.of(message("system", systemParts));
    }

    /** The text of a system turn's content, as sent: a string, or blocks, which may hold a nested system block. */
    private List<Map<String, Object>> systemParts(JsonValue content) {
        List<Map<String, Object>> systemParts = new ArrayList<>();
        Optional<String> string = AnthropicJson.string(content);
        if (string.isPresent()) {
            if (!string.get().isEmpty()) {
                systemParts.add(ContentParts.text(string.get()));
            }
            return systemParts;
        }
        for (JsonValue element : AnthropicJson.array(content).orElse(List.of())) {
            Map<String, JsonValue> block = AnthropicJson.object(element).orElse(Map.of());
            String type = AnthropicJson.type(element).orElse("(none)");
            switch (type) {
                case "text" ->
                    AnthropicJson.string(block.getOrDefault("text", JsonValue.from("")))
                            .filter(text -> !text.isEmpty())
                            .ifPresent(text -> systemParts.add(ContentParts.text(text)));
                case MID_CONV_SYSTEM ->
                    systemParts.addAll(systemParts(block.getOrDefault("content", JsonValue.from(List.of()))));
                default -> dropped.add("system content block " + type);
            }
        }
        return systemParts;
    }

    static Map<String, Object> message(String role, Object content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "message");
        message.put("role", role);
        message.put("content", content);
        return message;
    }
}
