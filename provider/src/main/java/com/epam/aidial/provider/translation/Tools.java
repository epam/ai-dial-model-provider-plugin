package com.epam.aidial.provider.translation;

import com.anthropic.models.beta.messages.BetaTool;
import com.anthropic.models.beta.messages.BetaToolChoice;
import com.anthropic.models.beta.messages.BetaToolUnion;
import com.anthropic.models.beta.messages.BetaUserLocation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The tools of a Messages API request, its {@code tool_choice} and what depends on them. A tool is read by its kind (see
 * {@link ToolKind}): a custom tool becomes a function tool, a web search the built-in one, any other is forwarded as
 * sent, for the deployment to refuse.
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
            translated.add(
                    switch (ToolKind.of(tool)) {
                        case CUSTOM -> function(tool.asBetaTool());
                        case WEB_SEARCH -> webSearch(tool);
                        case OTHER -> AnthropicJson.toMap(tool);
                    });
        }
        return translated;
    }

    private Map<String, Object> function(BetaTool tool) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("type", "function");
        function.put("name", names.toUpstream(tool.name()));
        function.put("parameters", parameters(tool));
        function.put("strict", tool.strict().orElse(false));
        tool.description().filter(description -> !description.isEmpty()).ifPresent(d -> function.put("description", d));
        return function;
    }

    /** {@code $schema} is valid JSON Schema, but OpenAI rejects function parameters that contain it. */
    private static Map<String, Object> parameters(BetaTool tool) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        if (tool._inputSchema().isMissing()) {
            parameters.put("type", "object");
            parameters.put("properties", new LinkedHashMap<>());
            return parameters;
        }
        AnthropicJson.toMap(tool.inputSchema()).forEach((key, value) -> {
            if (!"$schema".equals(key)) {
                parameters.put(key, value);
            }
        });
        return parameters;
    }

    private Map<String, Object> webSearch(BetaToolUnion tool) {
        Map<String, Object> search = new LinkedHashMap<>();
        search.put("type", WEB_SEARCH);
        tool.allowedDomains()
                .filter(domains -> !domains.isEmpty())
                .ifPresent(domains -> search.put("filters", Map.of("allowed_domains", domains)));
        tool.userLocation().ifPresent(location -> search.put("user_location", approximate(location)));
        if (tool.blockedDomains().filter(blocked -> !blocked.isEmpty()).isPresent()) {
            dropped.add("web_search blocked_domains: the search isn't restricted");
        }
        return search;
    }

    private static Map<String, Object> approximate(BetaUserLocation location) {
        Map<String, Object> approximate = new LinkedHashMap<>();
        approximate.put("type", "approximate");
        approximate.put("city", location.city().orElse(null));
        approximate.put("country", location.country().orElse(null));
        approximate.put("region", location.region().orElse(null));
        approximate.put("timezone", location.timezone().orElse(null));
        return approximate;
    }

    /**
     * {@code max_tool_calls} caps the calls of all the built-in tools together, which is the cap of a web search when
     * it is the only built-in tool.
     */
    Optional<Long> maxToolCalls(List<BetaToolUnion> tools) {
        List<BetaToolUnion> searches = tools.stream()
                .filter(tool -> ToolKind.of(tool) == ToolKind.WEB_SEARCH)
                .toList();
        if (searches.size() != 1) {
            if (searches.stream().anyMatch(search -> search.maxUses().isPresent())) {
                dropped.add("web_search max_uses: several web search tools can't share one max_tool_calls");
            }
            return Optional.empty();
        }
        return searches.getFirst().maxUses();
    }

    /** {@code tool_choice} as the Responses API says it; empty for a type it has no word for. */
    Optional<Object> toolChoice(BetaToolChoice choice, List<BetaToolUnion> tools) {
        return switch (choice.type().value()) {
            case AUTO -> Optional.of("auto");
            case ANY -> Optional.of("required");
            case NONE -> Optional.of("none");
            case TOOL -> Optional.of(namedTool(choice.asTool().name(), tools));
            case _UNKNOWN -> Optional.empty();
        };
    }

    private Object namedTool(String name, List<BetaToolUnion> tools) {
        boolean webSearch = tools.stream()
                .anyMatch(tool -> ToolKind.of(tool) == ToolKind.WEB_SEARCH
                        && tool.name().filter(name::equals).isPresent());
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
        return choice.disableParallelToolUse().map(disable -> !disable);
    }
}
