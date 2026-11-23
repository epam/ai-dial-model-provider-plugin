package com.epam.aidial.provider.translation;

import com.anthropic.core.JsonValue;
import com.anthropic.models.beta.messages.BetaToolChoice;
import com.anthropic.models.beta.messages.BetaToolUnion;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The tools of a Messages API request, its {@code tool_choice} and what depends on them. A tool is read as it was sent
 * (see {@link AnthropicJson#toolType}): a custom tool becomes a function tool, a web search the built-in one, any other
 * is forwarded as sent, for the deployment to refuse.
 */
final class Tools {

    private static final String WEB_SEARCH = "web_search";

    private final ToolNames names;
    private final Dropped dropped;

    Tools(ToolNames names, Dropped dropped) {
        this.names = names;
        this.dropped = dropped;
    }

    List<Object> translate(List<BetaToolUnion> tools) {
        List<Object> translated = new ArrayList<>();
        for (BetaToolUnion tool : tools) {
            String type = AnthropicJson.toolType(tool);
            Map<String, Object> sent = AnthropicJson.toMap(AnthropicJson.raw(tool));
            if (type.equals("custom")) {
                translated.add(function(sent));
            } else if (isWebSearch(type)) {
                translated.add(webSearch(sent));
            } else {
                translated.add(sent);
            }
        }
        return translated;
    }

    private Map<String, Object> function(Map<String, Object> tool) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("type", "function");
        function.put("name", names.toUpstream(string(tool, "name")));
        function.put("parameters", parameters(tool.get("input_schema")));
        function.put("strict", Boolean.TRUE.equals(tool.get("strict")));
        if (tool.get("description") instanceof String description && !description.isEmpty()) {
            function.put("description", description);
        }
        return function;
    }

    /** {@code $schema} is valid JSON Schema, but OpenAI rejects function parameters that contain it. */
    private static Map<String, Object> parameters(Object schema) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        if (schema instanceof Map<?, ?> map) {
            map.forEach((key, value) -> {
                if (!"$schema".equals(key)) {
                    parameters.put((String) key, value);
                }
            });
            return parameters;
        }
        parameters.put("type", "object");
        parameters.put("properties", new LinkedHashMap<>());
        return parameters;
    }

    private Map<String, Object> webSearch(Map<String, Object> tool) {
        Map<String, Object> search = new LinkedHashMap<>();
        search.put("type", WEB_SEARCH);
        if (tool.get("allowed_domains") instanceof List<?> domains && !domains.isEmpty()) {
            search.put("filters", Map.of("allowed_domains", domains));
        }
        if (tool.get("user_location") instanceof Map<?, ?> location && !location.isEmpty()) {
            Map<String, Object> approximate = new LinkedHashMap<>();
            approximate.put("type", "approximate");
            for (String key : List.of("city", "country", "region", "timezone")) {
                approximate.put(key, location.get(key));
            }
            search.put("user_location", approximate);
        }
        if (tool.get("blocked_domains") instanceof List<?> blocked && !blocked.isEmpty()) {
            dropped.add("web_search blocked_domains: the search isn't restricted");
        }
        return search;
    }

    /**
     * {@code max_tool_calls} caps the calls of all the built-in tools together, which is the cap of a web search when
     * it is the only built-in tool.
     */
    Optional<Object> maxToolCalls(List<BetaToolUnion> tools) {
        List<Map<String, Object>> searches = tools.stream()
                .filter(tool -> isWebSearch(AnthropicJson.toolType(tool)))
                .map(tool -> AnthropicJson.toMap(AnthropicJson.raw(tool)))
                .toList();
        if (searches.size() != 1) {
            if (searches.stream().anyMatch(search -> search.get("max_uses") != null)) {
                dropped.add("web_search max_uses: several web search tools can't share one max_tool_calls");
            }
            return Optional.empty();
        }
        return Optional.ofNullable(searches.getFirst().get("max_uses"));
    }

    /** {@code tool_choice} as the Responses API says it; empty for a type it has no word for. */
    Optional<Object> toolChoice(BetaToolChoice choice, List<BetaToolUnion> tools) {
        Map<String, Object> sent = AnthropicJson.toMap(choice._json().orElseThrow());
        String type = string(sent, "type");
        return switch (type) {
            case "auto" -> Optional.of("auto");
            case "any" -> Optional.of("required");
            case "none" -> Optional.of("none");
            case "tool" -> Optional.of(namedTool(string(sent, "name"), tools));
            default -> Optional.empty();
        };
    }

    private Object namedTool(String name, List<BetaToolUnion> tools) {
        boolean webSearch = tools.stream().anyMatch(tool -> {
            Map<String, Object> sent = AnthropicJson.toMap(AnthropicJson.raw(tool));
            return isWebSearch(AnthropicJson.toolType(tool)) && name.equals(sent.get("name"));
        });
        Map<String, Object> choice = new LinkedHashMap<>();
        if (webSearch) {
            // The tool is sent as the built-in web search, which a choice cannot name
            choice.put("type", "allowed_tools");
            choice.put("mode", "required");
            choice.put("tools", List.of(Map.of("type", WEB_SEARCH)));
        } else {
            choice.put("type", "function");
            choice.put("name", names.toUpstream(name));
        }
        return choice;
    }

    /** {@code parallel_tool_calls}, only when {@code tool_choice} says {@code disable_parallel_tool_use}. */
    static Optional<Boolean> parallelToolCalls(BetaToolChoice choice) {
        JsonValue disable = AnthropicJson.object(choice._json().orElseThrow())
                .map(o -> o.get("disable_parallel_tool_use"))
                .orElse(null);
        return disable == null
                ? Optional.empty()
                : Optional.of(!AnthropicJson.bool(disable).orElse(false));
    }

    private static boolean isWebSearch(String type) {
        return type.startsWith("web_search_");
    }

    private static String string(Map<String, Object> object, String name) {
        return object.get(name) instanceof String value ? value : "";
    }
}
