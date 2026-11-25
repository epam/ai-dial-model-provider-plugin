package com.epam.aidial.provider.translation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The paths of both bodies in a worked example: system [A, B] (one instruction); messages[0] "hi";
 * messages[1] [thinking (dropped), text "ok", tool_use]; messages[2] [tool_result, text "go"], translated into
 * instructions, input[0] user "hi", [1] assistant "ok", [2] function_call, [3] function_call_output, [4] user "go".
 */
class CachePathsTest {

    private static final CachePaths EXAMPLE =
            new CachePaths(1, 2, true, List.of(new int[] {0}, new int[] {-1, 1, 2}, new int[] {3, 4}));

    private static String body(String member) {
        return "prefix.body." + member;
    }

    private static Optional<String> toResponses(CachePaths paths, String member) {
        return paths.toResponses(body(member));
    }

    private static Optional<String> toMessages(CachePaths paths, String member) {
        return paths.toMessages(body(member));
    }

    @Test
    void toolIsTheToolWhenThereIsOne() {
        assertEquals(Optional.of(body("tools[0]")), toResponses(EXAMPLE, "tools[0]"));
        assertEquals(Optional.empty(), toResponses(EXAMPLE, "tools[1]"));
        assertEquals(Optional.of(body("tools[0]")), toMessages(EXAMPLE, "tools[0]"));
        assertEquals(Optional.empty(), toMessages(EXAMPLE, "tools[1]"));
    }

    @Test
    void everySystemBlockIsTheInstructionsAndTheInstructionsAreTheLast() {
        assertEquals(Optional.of(body("instructions[0]")), toResponses(EXAMPLE, "system[0]"));
        assertEquals(Optional.of(body("instructions[0]")), toResponses(EXAMPLE, "system[1]"));
        assertEquals(Optional.empty(), toResponses(EXAMPLE, "system[2]"));
        assertEquals(Optional.of(body("system[1]")), toMessages(EXAMPLE, "instructions[0]"));
        assertEquals(Optional.empty(), toMessages(EXAMPLE, "instructions[1]"));
    }

    @Test
    void blockIsItsItemAndItemIsItsLastBlock() {
        assertEquals(Optional.of(body("input[1]")), toResponses(EXAMPLE, "messages[1].content[1]"));
        assertEquals(Optional.of(body("input[2]")), toResponses(EXAMPLE, "messages[1].content[2]"));
        assertEquals(Optional.of(body("input[3]")), toResponses(EXAMPLE, "messages[2].content[0]"));
        assertEquals(Optional.of(body("input[4]")), toResponses(EXAMPLE, "messages[2].content[1]"));
        assertEquals(Optional.of(body("messages[0].content[0]")), toMessages(EXAMPLE, "input[0]"));
        assertEquals(Optional.of(body("messages[1].content[1]")), toMessages(EXAMPLE, "input[1]"));
        assertEquals(Optional.of(body("messages[1].content[2]")), toMessages(EXAMPLE, "input[2]"));
        assertEquals(Optional.of(body("messages[2].content[0]")), toMessages(EXAMPLE, "input[3]"));
        assertEquals(Optional.of(body("messages[2].content[1]")), toMessages(EXAMPLE, "input[4]"));
    }

    @Test
    void droppedBlockIsNearestKeptBlockBeforeIt() {
        assertEquals(Optional.of(body("input[0]")), toResponses(EXAMPLE, "messages[1].content[0]"));
    }

    @Test
    void droppedBlockWithNothingBeforeItFallsBackToInstructionsThenLastTool() {
        List<int[]> dropped = List.of(new int[] {-1}, new int[] {0});
        assertEquals(
                Optional.of(body("instructions[0]")),
                toResponses(new CachePaths(1, 1, true, dropped), "messages[0].content[0]"));
        assertEquals(
                Optional.of(body("tools[1]")),
                toResponses(new CachePaths(2, 0, false, dropped), "messages[0].content[0]"));
        assertEquals(Optional.empty(), toResponses(new CachePaths(0, 0, false, dropped), "messages[0].content[0]"));
    }

    @Test
    void systemWithoutInstructionsIsNothing() {
        CachePaths emptySystem = new CachePaths(0, 1, false, List.of(new int[] {0}));
        assertEquals(Optional.empty(), toResponses(emptySystem, "system[0]"));
        assertEquals(Optional.empty(), toMessages(emptySystem, "instructions[0]"));
    }

    @Test
    void anItemNoBlockWentIntoIsNothing() {
        assertEquals(Optional.empty(), toMessages(EXAMPLE, "input[5]"));
    }

    @Test
    void whatIsNoPathOfTheBodyIsNothing() {
        for (String member : new String[] {
            "metadata", "messages[9].content[0]", "messages[0].content[7]", "messages[x].content[0]", "input[0]"
        }) {
            assertEquals(Optional.empty(), toResponses(EXAMPLE, member), member);
        }
        for (String member : new String[] {"input", "input[-1]", "messages[0].content[0]", "output[0]"}) {
            assertEquals(Optional.empty(), toMessages(EXAMPLE, member), member);
        }
        assertEquals(Optional.empty(), EXAMPLE.toResponses("body.input[0]"));
        assertEquals(Optional.empty(), EXAMPLE.toMessages(""));
    }
}
