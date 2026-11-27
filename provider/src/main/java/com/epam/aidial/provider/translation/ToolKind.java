package com.epam.aidial.provider.translation;

import com.anthropic.models.beta.messages.BetaTool;
import com.anthropic.models.beta.messages.BetaToolUnion;

/** What a tool of the Messages request becomes. */
enum ToolKind {
    /** A client's tool, which becomes a function tool. */
    CUSTOM,
    /** The server's web search, which becomes the built-in one. */
    WEB_SEARCH,
    /** Any other tool: forwarded as sent, for the deployment to refuse. */
    OTHER;

    private static final String WEB_SEARCH_NAME = "web_search";

    static ToolKind of(BetaToolUnion tool) {
        if (tool.isBetaTool()) {
            // The SDK takes a tool of a type it does not know for a custom one: it is one only if its type says so
            boolean custom = tool.asBetaTool()
                    .type()
                    .map(type -> type.value() == BetaTool.Type.Value.CUSTOM)
                    .orElse(true);
            return custom ? CUSTOM : OTHER;
        }
        // Every version of the server's web search has the name
        return tool.name().filter(WEB_SEARCH_NAME::equals).isPresent() ? WEB_SEARCH : OTHER;
    }
}
