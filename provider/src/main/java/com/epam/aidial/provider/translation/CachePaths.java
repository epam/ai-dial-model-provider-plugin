package com.epam.aidial.provider.translation;

import com.epam.aidial.provider.api.BodyPath;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The paths of DIAL Core's cache affinity in the two bodies of one request. Core addresses a position of the body it
 * sent (a scalar counts as a one-element array: {@code system} as a string is {@code system[0]}, a string content is
 * {@code content[0]}), and asks the upstream to answer with the position that ends the prefix it cached.
 *
 * <ul>
 *   <li>Messages: {@code tools[k]}, {@code system[k]}, {@code messages[j].content[k]}
 *   <li>Responses: {@code tools[k]}, {@code instructions[0]} (all the system text is one string), {@code input[i]}
 * </ul>
 *
 * <p>The APIs do not line up one to one, so the translation of the request records where each block went, and a path
 * is carried over by it. Going to the Responses body, a block is the item it went into; one that was dropped is what
 * the nearest block before it is (a prefix is still a prefix), else the instructions, else the last tool, else
 * nothing. Coming back, an item is the last block that went into it (one item may hold several), the instructions are
 * the last system block. A path that names nothing is {@code empty}: the header is left out.
 */
final class CachePaths {

    private static final Pattern MESSAGES_TOOL = Pattern.compile("^prefix\\.body\\.tools\\[(\\d+)]$");
    private static final Pattern MESSAGES_SYSTEM = Pattern.compile("^prefix\\.body\\.system\\[(\\d+)]$");
    private static final Pattern MESSAGES_BLOCK =
            Pattern.compile("^prefix\\.body\\.messages\\[(\\d+)]\\.content\\[(\\d+)]$");
    private static final Pattern RESPONSES_PATH =
            Pattern.compile("^prefix\\.body\\.(tools|instructions|input)\\[(\\d+)]$");

    private final int tools;
    private final int systemBlocks;
    private final boolean hasInstructions;
    private final List<int[]> itemOfBlock;

    /**
     * @param systemBlocks the blocks of {@code system} (a string is one)
     * @param itemOfBlock for each message, the {@code input} item each of its blocks went into, {@code -1} for none
     */
    CachePaths(int tools, int systemBlocks, boolean hasInstructions, List<int[]> itemOfBlock) {
        this.tools = tools;
        this.systemBlocks = systemBlocks;
        this.hasInstructions = hasInstructions;
        this.itemOfBlock = itemOfBlock;
    }

    Optional<String> toResponses(String path) {
        Matcher tool = MESSAGES_TOOL.matcher(path);
        if (tool.matches()) {
            int k = Integer.parseInt(tool.group(1));
            return k < tools ? Optional.of(path(BodyPath.ROOT.member("tools").index(k))) : Optional.empty();
        }
        Matcher system = MESSAGES_SYSTEM.matcher(path);
        if (system.matches()) {
            return Integer.parseInt(system.group(1)) < systemBlocks && hasInstructions
                    ? Optional.of(path(instructions()))
                    : Optional.empty();
        }
        Matcher block = MESSAGES_BLOCK.matcher(path);
        if (block.matches()) {
            int j = Integer.parseInt(block.group(1));
            int k = Integer.parseInt(block.group(2));
            if (j >= itemOfBlock.size() || k >= itemOfBlock.get(j).length) {
                return Optional.empty();
            }
            int item = nearestKeptItem(j, k);
            if (item >= 0) {
                return Optional.of(path(BodyPath.ROOT.member("input").index(item)));
            }
            if (hasInstructions) {
                return Optional.of(path(instructions()));
            }
            return tools > 0 ? Optional.of(path(BodyPath.ROOT.member("tools").index(tools - 1))) : Optional.empty();
        }
        return Optional.empty();
    }

    /** The item of the block, or of the nearest block before it that went into one; -1 for none. */
    private int nearestKeptItem(int message, int block) {
        for (int j = message; j >= 0; j--) {
            int[] blocks = itemOfBlock.get(j);
            for (int k = j == message ? block : blocks.length - 1; k >= 0; k--) {
                if (blocks[k] >= 0) {
                    return blocks[k];
                }
            }
        }
        return -1;
    }

    Optional<String> toMessages(String path) {
        Matcher m = RESPONSES_PATH.matcher(path);
        if (!m.matches()) {
            return Optional.empty();
        }
        int index = Integer.parseInt(m.group(2));
        switch (m.group(1)) {
            case "tools" -> {
                return index < tools
                        ? Optional.of(path(BodyPath.ROOT.member("tools").index(index)))
                        : Optional.empty();
            }
            case "instructions" -> {
                return index == 0 && hasInstructions && systemBlocks > 0
                        ? Optional.of(path(BodyPath.ROOT.member("system").index(systemBlocks - 1)))
                        : Optional.empty();
            }
            default -> {
                for (int j = itemOfBlock.size() - 1; j >= 0; j--) {
                    int[] blocks = itemOfBlock.get(j);
                    for (int k = blocks.length - 1; k >= 0; k--) {
                        if (blocks[k] == index) {
                            return Optional.of(path(BodyPath.ROOT
                                    .member("messages")
                                    .index(j)
                                    .member("content")
                                    .index(k)));
                        }
                    }
                }
                return Optional.empty();
            }
        }
    }

    private static BodyPath instructions() {
        return BodyPath.ROOT.member("instructions").index(0);
    }

    private static String path(BodyPath path) {
        return path.value();
    }
}
