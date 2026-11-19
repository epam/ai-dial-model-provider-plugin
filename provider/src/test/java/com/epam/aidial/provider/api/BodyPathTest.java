package com.epam.aidial.provider.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BodyPathTest {

    @Test
    void positionsAreAddressedFromThePrefix() {
        assertEquals("prefix.body", BodyPath.ROOT.toString());
        assertEquals(
                "prefix.body.input[0]", BodyPath.ROOT.member("input").index(0).value());
        assertEquals(
                "prefix.body.messages[1].content[2]",
                BodyPath.ROOT
                        .member("messages")
                        .index(1)
                        .member("content")
                        .index(2)
                        .toString());
    }
}
