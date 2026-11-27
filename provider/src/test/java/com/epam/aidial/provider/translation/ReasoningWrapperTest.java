package com.epam.aidial.provider.translation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The format is the contract with the next turn, and with the Python translator: pinned to the character. */
class ReasoningWrapperTest {

    @Test
    void encodedAsCompactJsonInTheDeclaredOrder() {
        assertEquals(
                "{\"dial_translator_reasoning_wrapper\":{\"version\":2,\"id\":\"rs_1\",\"encrypted_content\":\"ENC\"}}",
                new ReasoningWrapper("rs_1", "ENC").encode());
    }

    @Test
    void decodedBackWhatItEncodes() {
        ReasoningWrapper wrapper = new ReasoningWrapper("rs_\"1\"", "a\nb");
        assertEquals(Optional.of(wrapper), ReasoningWrapper.decode(wrapper.encode()));
    }

    @Test
    void decodedWithExtraMembersAndAnyOrder() {
        String signature = "{\"x\":1,\"dial_translator_reasoning_wrapper\":"
                + "{\"encrypted_content\":\"E\",\"extra\":[],\"id\":\"i\",\"version\":2}}";
        assertEquals(Optional.of(new ReasoningWrapper("i", "E")), ReasoningWrapper.decode(signature));
    }

    @Test
    void nothingElseIsWrapper() {
        for (String signature : new String[] {
            "",
            "EqQBCkYIAxgCIkD",
            "{}",
            "[]",
            "null",
            "{\"dial_translator_reasoning_wrapper\":null}",
            "{\"dial_translator_reasoning_wrapper\":{\"version\":1,\"id\":\"i\",\"encrypted_content\":\"E\"}}",
            "{\"dial_translator_reasoning_wrapper\":{\"version\":\"2\",\"id\":\"i\",\"encrypted_content\":\"E\"}}",
            "{\"dial_translator_reasoning_wrapper\":{\"version\":2,\"id\":1,\"encrypted_content\":\"E\"}}",
            "{\"dial_translator_reasoning_wrapper\":{\"version\":2,\"id\":\"i\"}}",
            "{\"dial_translator_reasoning_wrapper\":{\"version\":2,\"id\":\"i\",\"encrypted_content\":\"E\"}}x"
        }) {
            assertTrue(ReasoningWrapper.decode(signature).isEmpty(), signature);
        }
    }
}
