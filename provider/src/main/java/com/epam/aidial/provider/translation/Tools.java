package com.epam.aidial.provider.translation;

import com.anthropic.models.beta.messages.BetaTool;
import com.anthropic.models.beta.messages.BetaToolChoice;
import com.anthropic.models.beta.messages.BetaToolUnion;
import com.anthropic.models.beta.messages.BetaUserLocation;
import com.openai.core.JsonValue;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.Tool;
import com.openai.models.responses.ToolChoiceAllowed;
import com.openai.models.responses.ToolChoiceFunction;
import com.openai.models.responses.ToolChoiceOptions;
import com.openai.models.responses.WebSearchTool;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The tools of a Messages API request, its {@code tool_choice} and what depends on them. A tool is read by its kind
 * (see {@link ToolKind}): a custom tool becomes a function tool, a web search the built-in one, any other is forwarded
 * as sent, for the deployment to refuse.
 */
final class Tools {

    private static final String WEB_SEARCH = "web_search";

    private final ToolNames names;
    private final Dropped dropped;

    Tools(ToolNames names, Dropped dropped) {
        this.names = names;
        this.dropped = dropped;
    }

    List<Tool> translate(List<BetaToolUnion> tools) {
        List<Tool> translated = new ArrayList<>();
        for (BetaToolUnion tool : tools) {
            translated.add(
                    switch (ToolKind.of(tool)) {
                        case CUSTOM -> Tool.ofFunction(function(tool.asBetaTool()));
                        case WEB_SEARCH -> Tool.ofWebSearch(webSearch(tool));
                        // The deployment is to refuse it: it is sent as it is
                        case OTHER -> ResponsesJson.fromAnthropic(tool, Tool.class);
                    });
        }
        return translated;
    }

    private FunctionTool function(BetaTool tool) {
        FunctionTool.Builder function = FunctionTool.builder()
                .name(names.toUpstream(tool.name()))
                .parameters(parameters(tool))
                .strict(tool.strict().orElse(false));
        tool.description().filter(description -> !description.isEmpty()).ifPresent(function::description);
        return function.build();
    }

    /** {@code $schema} is valid JSON Schema, but OpenAI rejects function parameters that contain it. */
    private static FunctionTool.Parameters parameters(BetaTool tool) {
        if (tool._inputSchema().isMissing()) {
            return FunctionTool.Parameters.builder()
                    .putAdditionalProperty("type", JsonValue.from("object"))
                    .putAdditionalProperty("properties", JsonValue.from(Map.of()))
                    .build();
        }
        return ResponsesJson.fromAnthropic(tool.inputSchema(), FunctionTool.Parameters.class).toBuilder()
                .removeAdditionalProperty("$schema")
                .build();
    }

    private WebSearchTool webSearch(BetaToolUnion tool) {
        WebSearchTool.Builder search = WebSearchTool.builder().type(WebSearchTool.Type.WEB_SEARCH);
        tool.allowedDomains()
                .filter(domains -> !domains.isEmpty())
                .ifPresent(domains -> search.filters(
                        WebSearchTool.Filters.builder().allowedDomains(domains).build()));
        tool.userLocation().ifPresent(location -> search.userLocation(approximate(location)));
        if (tool.blockedDomains().filter(blocked -> !blocked.isEmpty()).isPresent()) {
            dropped.add("web_search blocked_domains: the search isn't restricted");
        }
        return search.build();
    }

    private static WebSearchTool.UserLocation approximate(BetaUserLocation location) {
        // What the request does not give is null, not left out
        return WebSearchTool.UserLocation.builder()
                .type(WebSearchTool.UserLocation.Type.APPROXIMATE)
                .city(location.city())
                .country(location.country())
                .region(location.region())
                .timezone(location.timezone())
                .build();
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
    Optional<ResponseCreateParams.ToolChoice> toolChoice(BetaToolChoice choice, List<BetaToolUnion> tools) {
        return switch (choice.type().value()) {
            case AUTO -> Optional.of(ResponseCreateParams.ToolChoice.ofOptions(ToolChoiceOptions.AUTO));
            case ANY -> Optional.of(ResponseCreateParams.ToolChoice.ofOptions(ToolChoiceOptions.REQUIRED));
            case NONE -> Optional.of(ResponseCreateParams.ToolChoice.ofOptions(ToolChoiceOptions.NONE));
            case TOOL -> Optional.of(namedTool(choice.asTool().name(), tools));
            case _UNKNOWN -> Optional.empty();
        };
    }

    private ResponseCreateParams.ToolChoice namedTool(String name, List<BetaToolUnion> tools) {
        boolean webSearch = tools.stream()
                .anyMatch(tool -> ToolKind.of(tool) == ToolKind.WEB_SEARCH
                        && tool.name().filter(name::equals).isPresent());
        if (webSearch) {
            // The tool is sent as the built-in web search, which a choice cannot name
            return ResponseCreateParams.ToolChoice.ofAllowed(ToolChoiceAllowed.builder()
                    .mode(ToolChoiceAllowed.Mode.REQUIRED)
                    .addTool(ToolChoiceAllowed.Tool.builder()
                            .putAdditionalProperty("type", JsonValue.from(WEB_SEARCH))
                            .build())
                    .build());
        }
        return ResponseCreateParams.ToolChoice.ofFunction(
                ToolChoiceFunction.builder().name(names.toUpstream(name)).build());
    }

    /** {@code parallel_tool_calls}, only when {@code tool_choice} says {@code disable_parallel_tool_use}. */
    static Optional<Boolean> parallelToolCalls(BetaToolChoice choice) {
        return choice.disableParallelToolUse().map(disable -> !disable);
    }
}
