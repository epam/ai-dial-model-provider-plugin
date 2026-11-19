package com.epam.aidial.provider.testing.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** An absent actual value is {@code null}; JSON null is a NullNode. */
public final class JsonMatch {

    /** {@code actual} is null when absent. */
    @FunctionalInterface
    public interface Operator {
        void apply(JsonMatch m, JsonNode argument, JsonNode actual, String path);
    }

    public static final ObjectMapper JSON = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)
            .build();

    static Map<String, Operator> operators() {
        return Map.of(
                "$type", JsonMatch::type,
                "$regex", JsonMatch::regex,
                "$partial", JsonMatch::partial,
                "$absent", JsonMatch::absent,
                "$unordered", JsonMatch::unordered,
                "$json", JsonMatch::json,
                "$literal", (m, argument, actual, path) -> m.match(argument, actual, path, true),
                "$one_of", JsonMatch::oneOf);
    }

    private final boolean strictMemberOrder;
    private final Map<String, Operator> operators;
    private final List<String> failures = new ArrayList<>();

    public JsonMatch(boolean strictMemberOrder, Map<String, Operator> operators) {
        this.strictMemberOrder = strictMemberOrder;
        this.operators = operators;
    }

    public List<String> failures() {
        return failures;
    }

    public List<String> check(JsonNode expected, JsonNode actual, String path) {
        match(expected, actual, path, false);
        return failures;
    }

    public void fail(String path, String message) {
        failures.add(path + ": " + message);
    }

    public JsonMatch child() {
        return new JsonMatch(strictMemberOrder, operators);
    }

    public static boolean isMatcher(JsonNode node) {
        if (node == null || !node.isObject() || node.isEmpty()) {
            return false;
        }
        for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
            if (!names.next().startsWith("$")) {
                return false;
            }
        }
        return true;
    }

    public void match(JsonNode expected, JsonNode actual, String path, boolean literal) {
        if (!literal && isMatcher(expected)) {
            expected.properties().forEach(e -> {
                Operator op = operators.get(e.getKey());
                if (op == null) {
                    fail(path, "unknown matcher " + e.getKey() + " (known: " + operators.keySet() + ")");
                } else {
                    op.apply(this, e.getValue(), actual, path);
                }
            });
            return;
        }
        if (actual == null) {
            fail(path, "missing, expected " + show(expected));
            return;
        }
        if (expected.isObject()) {
            if (!actual.isObject()) {
                fail(path, "expected an object " + show(expected) + " but was " + show(actual));
                return;
            }
            expected.properties()
                    .forEach(e -> match(e.getValue(), actual.get(e.getKey()), path + "." + e.getKey(), literal));
            List<String> common = new ArrayList<>();
            actual.fieldNames().forEachRemaining(name -> {
                if (expected.has(name)) {
                    common.add(name);
                } else {
                    fail(path + "." + name, "unexpected member " + show(actual.get(name)));
                }
            });
            List<String> declared = new ArrayList<>();
            expected.fieldNames().forEachRemaining(name -> {
                if (actual.has(name)) {
                    declared.add(name);
                }
            });
            if (strictMemberOrder && !declared.equals(common)) {
                fail(path, "member order " + common + ", expected " + declared);
            }
        } else if (expected.isArray()) {
            if (!actual.isArray()) {
                fail(path, "expected an array " + show(expected) + " but was " + show(actual));
                return;
            }
            if (expected.size() != actual.size()) {
                fail(path, "expected " + expected.size() + " elements but was " + actual.size() + ": " + show(actual));
            }
            for (int i = 0; i < Math.min(expected.size(), actual.size()); i++) {
                match(expected.get(i), actual.get(i), path + "[" + i + "]", literal);
            }
        } else if (expected.isNumber()) {
            if (!actual.isNumber()
                    || expected.isIntegralNumber() != actual.isIntegralNumber()
                    || expected.decimalValue().compareTo(actual.decimalValue()) != 0) {
                fail(path, "expected " + show(expected) + " but was " + show(actual));
            }
        } else if (expected.isTextual() && actual.isTextual() && !expected.equals(actual)) {
            fail(path, textDifference(expected.asText(), actual.asText()));
        } else if (!expected.equals(actual)) {
            fail(path, "expected " + show(expected) + " but was " + show(actual));
        }
    }

    static String textDifference(String expected, String actual) {
        if (expected.length() + actual.length() <= 240) {
            return "expected " + TextNode.valueOf(expected) + " but was " + TextNode.valueOf(actual);
        }
        int at = 0;
        while (at < Math.min(expected.length(), actual.length()) && expected.charAt(at) == actual.charAt(at)) {
            at++;
        }
        int from = Math.max(0, at - 40);
        return "differs at character " + at + " (lengths " + expected.length() + " and " + actual.length()
                + "): expected ..." + TextNode.valueOf(expected.substring(from, Math.min(expected.length(), at + 60)))
                + "... but was ..." + TextNode.valueOf(actual.substring(from, Math.min(actual.length(), at + 60)))
                + "...";
    }

    private static void type(JsonMatch m, JsonNode argument, JsonNode actual, String path) {
        List<String> allowed = new ArrayList<>();
        if (argument.isArray()) {
            argument.forEach(t -> allowed.add(t.asText()));
        } else {
            allowed.add(argument.asText());
        }
        String type = actual == null ? "absent" : typeOf(actual);
        if (!allowed.contains(type) && !(type.equals("integer") && allowed.contains("number"))) {
            m.fail(path, "expected a value of type " + String.join(" or ", allowed) + " but was " + show(actual));
        }
    }

    private static String typeOf(JsonNode node) {
        return switch (node.getNodeType()) {
            case STRING -> "string";
            case NUMBER -> node.isIntegralNumber() ? "integer" : "number";
            case BOOLEAN -> "boolean";
            case NULL -> "null";
            case OBJECT -> "object";
            case ARRAY -> "array";
            default -> node.getNodeType().name().toLowerCase(Locale.ROOT);
        };
    }

    private static void regex(JsonMatch m, JsonNode argument, JsonNode actual, String path) {
        if (actual == null || !actual.isTextual()) {
            m.fail(path, "expected a string matching /" + argument.asText() + "/ but was " + show(actual));
        } else if (!Pattern.compile(argument.asText()).matcher(actual.asText()).find()) {
            m.fail(path, "expected a match of /" + argument.asText() + "/ but was " + show(actual));
        }
    }

    private static void partial(JsonMatch m, JsonNode argument, JsonNode actual, String path) {
        if (actual == null || !actual.isObject()) {
            m.fail(path, "expected an object with " + show(argument) + " but was " + show(actual));
            return;
        }
        argument.properties()
                .forEach(e -> m.match(e.getValue(), actual.get(e.getKey()), path + "." + e.getKey(), false));
    }

    private static void absent(JsonMatch m, JsonNode argument, JsonNode actual, String path) {
        if (actual != null) {
            m.fail(path, "expected no value but was " + show(actual));
        }
    }

    private static void unordered(JsonMatch m, JsonNode argument, JsonNode actual, String path) {
        if (actual == null || !actual.isArray()) {
            m.fail(path, "expected an array but was " + show(actual));
            return;
        }
        if (argument.size() != actual.size()) {
            m.fail(path, "expected " + argument.size() + " elements but was " + actual.size() + ": " + show(actual));
        }
        boolean[] used = new boolean[actual.size()];
        for (int i = 0; i < argument.size(); i++) {
            boolean found = false;
            for (int j = 0; j < actual.size() && !found; j++) {
                if (!used[j]
                        && m.child().check(argument.get(i), actual.get(j), path).isEmpty()) {
                    used[j] = found = true;
                }
            }
            if (!found) {
                m.fail(path, "no element matches " + show(argument.get(i)) + " in " + show(actual));
            }
        }
    }

    private static void json(JsonMatch m, JsonNode argument, JsonNode actual, String path) {
        if (actual == null || !actual.isTextual()) {
            m.fail(path, "expected a JSON string but was " + show(actual));
            return;
        }
        JsonNode parsed;
        try {
            parsed = JSON.readTree(actual.asText());
        } catch (JsonProcessingException e) {
            m.fail(path, "not JSON (" + e.getOriginalMessage() + "): " + show(actual));
            return;
        }
        m.match(argument, parsed, path + "<json>", false);
    }

    private static void oneOf(JsonMatch m, JsonNode argument, JsonNode actual, String path) {
        if (!argument.isArray() || argument.isEmpty()) {
            m.fail(path, "$one_of takes an array of alternatives, not " + show(argument));
            return;
        }
        List<String> misses = new ArrayList<>();
        for (JsonNode alternative : argument) {
            List<String> failures = m.child().check(alternative, actual, path);
            if (failures.isEmpty()) {
                return;
            }
            misses.add("[" + misses.size() + "] " + String.join("; ", failures));
        }
        m.fail(path, "matches none of " + argument.size() + " alternatives: " + String.join(" | ", misses));
    }

    /** Compact JSON, cut at 300 characters. */
    public static String show(JsonNode node) {
        if (node == null) {
            return "<absent>";
        }
        String text = node.toString();
        return text.length() > 300 ? text.substring(0, 300) + "...(" + text.length() + " chars)" : text;
    }
}
