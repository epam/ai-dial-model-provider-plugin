package com.epam.aidial.provider.translation;

import com.epam.aidial.provider.json.Json;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The encrypted reasoning item of the Responses API, as the {@code signature} of an Anthropic thinking block (or the
 * {@code data} of a redacted one) that carries it to the next turn:
 * {@code {"dial_translator_reasoning_wrapper":{"version":2,"id":"rs_1","encrypted_content":"..."}}}. Only a signature of
 * this shape can be replayed; any other, a Claude one for instance, is not a reasoning item another provider can use.
 */
record ReasoningWrapper(String id, String encryptedContent) {

    private static final String KEY = "dial_translator_reasoning_wrapper";
    private static final int VERSION = 2;

    String encode() {
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("version", VERSION);
        wrapper.put("id", id);
        wrapper.put("encrypted_content", encryptedContent);
        return Json.encode(Map.of(KEY, wrapper));
    }

    /** Empty for anything that is not a wrapper of this version, whatever else it is. */
    static Optional<ReasoningWrapper> decode(String signature) {
        Object parsed;
        try {
            parsed = Json.parse(signature);
        } catch (Json.ParseException e) {
            return Optional.empty();
        }
        if (parsed instanceof Map<?, ?> outer
                && outer.get(KEY) instanceof Map<?, ?> wrapper
                && Integer.valueOf(VERSION).equals(wrapper.get("version"))
                && wrapper.get("id") instanceof String id
                && wrapper.get("encrypted_content") instanceof String encrypted) {
            return Optional.of(new ReasoningWrapper(id, encrypted));
        }
        return Optional.empty();
    }
}
