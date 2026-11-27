package com.epam.aidial.provider.translation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The alias is a value of its own, which the cases only see as a whole: its shape is pinned here. */
class ToolNamesTest {

    @Test
    void nameTheApiTakesStaysAsItIs() {
        ToolNames names = new ToolNames();
        for (String name : new String[] {"f", "get_weather", "_x", "a-b", "A1", "x".repeat(64), ""}) {
            assertEquals(name, names.toUpstream(name));
            assertEquals(name, names.toClient(name));
        }
    }

    @Test
    void aliasIsAtMost64CharactersOfAllowedOnes() {
        ToolNames names = new ToolNames();
        for (String name : new String[] {"a b", "1st", "x".repeat(65), "naïve ✓ tool", "😀😀", "-dash"}) {
            String alias = names.toUpstream(name);
            assertTrue(alias.matches("^[a-zA-Z_][a-zA-Z0-9_-]{0,63}$"), alias);
            assertEquals(name, names.toClient(alias));
        }
    }

    @Test
    void aliasOfDigitOrDashLeadHasPrefix() {
        ToolNames names = new ToolNames();
        assertTrue(names.toUpstream("1st").startsWith("t_1st_"));
        assertTrue(names.toUpstream("-dash").startsWith("t_-dash_"));
    }

    @Test
    void namesThatDifferHaveAliasesThatDiffer() {
        ToolNames names = new ToolNames();
        assertNotEquals(names.toUpstream("a b"), names.toUpstream("a.b"));
        assertEquals(names.toUpstream("a b"), names.toUpstream("a b"));
    }

    @Test
    void aliasesAreNotSharedBetweenRequests() {
        String alias = new ToolNames().toUpstream("a b");
        assertEquals(alias, new ToolNames().toClient(alias), "another request knows no alias of this one");
    }
}
