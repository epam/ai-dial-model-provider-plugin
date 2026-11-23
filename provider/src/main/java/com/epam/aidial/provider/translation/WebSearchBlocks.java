package com.epam.aidial.provider.translation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What a web search leaves of itself in a Responses API answer, URL citations, as the Messages API says a web search:
 * a {@code server_tool_use} and the {@code web_search_tool_result} that answers it. The query is not known.
 */
final class WebSearchBlocks {

    record Citation(String url, String title) {}

    private WebSearchBlocks() {}

    /** The pair for the citations, in order; none for none. */
    static List<Map<String, Object>> of(List<Citation> citations) {
        if (citations.isEmpty()) {
            return List.of();
        }
        String id = "srvtoolu_" + UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> use = new LinkedHashMap<>();
        use.put("type", "server_tool_use");
        use.put("id", id);
        use.put("name", "web_search");
        use.put("input", Map.of("query", ""));
        List<Map<String, Object>> results = new ArrayList<>();
        for (Citation citation : citations) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("type", "web_search_result");
            result.put("url", citation.url());
            result.put("title", citation.title());
            result.put("encrypted_content", "");
            results.add(result);
        }
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("type", "web_search_tool_result");
        answer.put("tool_use_id", id);
        answer.put("content", results);
        return List.of(use, answer);
    }
}
