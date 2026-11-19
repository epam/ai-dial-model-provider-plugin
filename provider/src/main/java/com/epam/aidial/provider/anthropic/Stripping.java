package com.epam.aidial.provider.anthropic;

import com.epam.aidial.provider.json.BodyFields;
import com.epam.aidial.provider.json.Json;
import com.epam.aidial.provider.json.JsonArrays;
import com.google.common.primitives.Bytes;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class Stripping {

    private Stripping() {}

    static boolean dropServerTools(BodyFields fields, Set<String> names) {
        if (names.isEmpty()) {
            return false;
        }
        byte[] raw = fields.get("tools");
        // A byte scan for the names first: a body that names none is not read tool by tool.
        if (raw == null || !mentionsAny(raw, names)) {
            return false;
        }
        byte[] kept = dropTools(raw, names);
        if (kept == null) {
            return false;
        }
        fields.set("tools", kept);
        return true;
    }

    private static boolean mentionsAny(byte[] raw, Set<String> names) {
        for (String name : names) {
            if (Bytes.indexOf(raw, ("\"" + name + "\"").getBytes(StandardCharsets.UTF_8)) >= 0) {
                return true;
            }
        }
        return false;
    }

    /** Null when none is removed or raw is not an array. A custom or untyped tool that shares a name stays. */
    static byte[] dropTools(byte[] raw, Set<String> names) {
        List<byte[]> tools = JsonArrays.elements(raw);
        if (tools == null) {
            return null;
        }
        List<byte[]> kept = new ArrayList<>(tools.size());
        for (byte[] tool : tools) {
            if (!isDroppedTool(tool, names)) {
                kept.add(tool);
            }
        }
        return kept.size() == tools.size() ? null : JsonArrays.array(kept);
    }

    private static boolean isDroppedTool(byte[] tool, Set<String> names) {
        Object v = Json.parseOrNull(tool);
        if (!(v instanceof Map<?, ?> m) || !(m.get("name") instanceof String name) || !names.contains(name)) {
            return false;
        }
        Object type = m.get("type");
        return type != null && !"custom".equals(type);
    }

    /** Empty when nothing is left: an empty anthropic-beta is rejected upstream, so the caller drops the header. */
    static String dropBetas(String value, Set<String> drop) {
        List<String> kept = new ArrayList<>();
        for (String flag : value.split(",", -1)) {
            String f = flag.strip();
            if (!f.isEmpty() && !drop.contains(f)) {
                kept.add(f);
            }
        }
        return String.join(",", kept);
    }
}
