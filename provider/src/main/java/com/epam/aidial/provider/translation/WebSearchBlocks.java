package com.epam.aidial.provider.translation;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ServerToolUseBlock;
import com.anthropic.models.messages.WebSearchResultBlock;
import com.anthropic.models.messages.WebSearchToolResultBlock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What a web search leaves of itself in a Responses API answer, URL citations, as the Messages API says a web search:
 * a {@code server_tool_use} and the {@code web_search_tool_result} that answers it. The query is not known.
 */
final class WebSearchBlocks {

    record Citation(String url, String title) {}

    private WebSearchBlocks() {}

    /** The pair for the citations, in order; none for none. */
    static List<ContentBlock> of(List<Citation> citations) {
        if (citations.isEmpty()) {
            return List.of();
        }
        String id = "srvtoolu_" + UUID.randomUUID().toString().replace("-", "");
        ServerToolUseBlock use = ServerToolUseBlock.builder()
                .id(id)
                .caller(Blocks.direct())
                .name(ServerToolUseBlock.Name.WEB_SEARCH)
                .input(JsonValue.from(Map.of("query", "")))
                .build();
        List<WebSearchResultBlock> results = citations.stream()
                .map(citation -> WebSearchResultBlock.builder()
                        .url(citation.url())
                        .title(citation.title())
                        .encryptedContent("")
                        .pageAge(Optional.empty())
                        .build())
                .toList();
        WebSearchToolResultBlock answer = WebSearchToolResultBlock.builder()
                .caller(Blocks.direct())
                .toolUseId(id)
                .contentOfResultBlocks(results)
                .build();
        return List.of(ContentBlock.ofServerToolUse(use), ContentBlock.ofWebSearchToolResult(answer));
    }
}
