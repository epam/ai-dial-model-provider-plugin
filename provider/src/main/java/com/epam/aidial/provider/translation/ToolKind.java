package com.epam.aidial.provider.translation;

import com.anthropic.core.JsonValue;
import com.anthropic.models.beta.messages.BetaAdvisorTool20260301;
import com.anthropic.models.beta.messages.BetaBrowserToolset20260801;
import com.anthropic.models.beta.messages.BetaCodeExecutionTool20250522;
import com.anthropic.models.beta.messages.BetaCodeExecutionTool20250825;
import com.anthropic.models.beta.messages.BetaCodeExecutionTool20260120;
import com.anthropic.models.beta.messages.BetaCodeExecutionTool20260521;
import com.anthropic.models.beta.messages.BetaComputerToolset20260801;
import com.anthropic.models.beta.messages.BetaMcpToolset;
import com.anthropic.models.beta.messages.BetaMemoryTool20250818;
import com.anthropic.models.beta.messages.BetaTool;
import com.anthropic.models.beta.messages.BetaToolBash20241022;
import com.anthropic.models.beta.messages.BetaToolBash20250124;
import com.anthropic.models.beta.messages.BetaToolComputerUse20241022;
import com.anthropic.models.beta.messages.BetaToolComputerUse20250124;
import com.anthropic.models.beta.messages.BetaToolComputerUse20251124;
import com.anthropic.models.beta.messages.BetaToolSearchToolBm25_20251119;
import com.anthropic.models.beta.messages.BetaToolSearchToolRegex20251119;
import com.anthropic.models.beta.messages.BetaToolTextEditor20241022;
import com.anthropic.models.beta.messages.BetaToolTextEditor20250124;
import com.anthropic.models.beta.messages.BetaToolTextEditor20250429;
import com.anthropic.models.beta.messages.BetaToolTextEditor20250728;
import com.anthropic.models.beta.messages.BetaToolUnion;
import com.anthropic.models.beta.messages.BetaWebFetchTool20250910;
import com.anthropic.models.beta.messages.BetaWebFetchTool20260209;
import com.anthropic.models.beta.messages.BetaWebFetchTool20260309;
import com.anthropic.models.beta.messages.BetaWebFetchTool20260318;
import com.anthropic.models.beta.messages.BetaWebSearchTool20250305;
import com.anthropic.models.beta.messages.BetaWebSearchTool20260209;
import com.anthropic.models.beta.messages.BetaWebSearchTool20260318;

/**
 * What a tool of the Messages request becomes. The choice is made by the SDK's visitor, which has a method for each
 * tool it models: a version of the API with a new tool does not compile until the new tool is placed here.
 */
enum ToolKind {
    /** A client's tool, which becomes a function tool. */
    CUSTOM,
    /** The server's web search, which becomes the built-in one. */
    WEB_SEARCH,
    /** Any other tool: forwarded as sent, for the deployment to refuse. */
    OTHER;

    static ToolKind of(BetaToolUnion tool) {
        return tool.accept(Kinds.INSTANCE);
    }

    private enum Kinds implements BetaToolUnion.Visitor<ToolKind> {
        INSTANCE;

        @Override
        public ToolKind visitBetaTool(BetaTool tool) {
            // The SDK takes a tool of a type it does not know for a custom one: it is one only if its type says so
            return tool.type().map(type -> type.value() == BetaTool.Type.Value.CUSTOM).orElse(true) ? CUSTOM : OTHER;
        }

        @Override
        public ToolKind visitBash20241022(BetaToolBash20241022 tool) {
            return OTHER;
        }

        @Override
        public ToolKind visitBash20250124(BetaToolBash20250124 tool) {
            return OTHER;
        }

        @Override
        public ToolKind visitCodeExecutionTool20250522(BetaCodeExecutionTool20250522 tool) {
            return OTHER;
        }

        @Override
        public ToolKind visitCodeExecutionTool20250825(BetaCodeExecutionTool20250825 tool) {
            return OTHER;
        }

        @Override
        public ToolKind visitCodeExecutionTool20260120(BetaCodeExecutionTool20260120 tool) {
            return OTHER;
        }

        @Override
        public ToolKind visitCodeExecutionTool20260521(BetaCodeExecutionTool20260521 tool) {
            return OTHER;
        }

        @Override
        public ToolKind visitBrowserToolset20260801(BetaBrowserToolset20260801 tool) {
            return OTHER;
        }

        @Override
        public ToolKind visitComputerUse20241022(BetaToolComputerUse20241022 tool) {
            return OTHER;
        }

        @Override
        public ToolKind visitMemoryTool20250818(BetaMemoryTool20250818 tool) {
            return OTHER;
        }

        @Override
        public ToolKind visitComputerUse20250124(BetaToolComputerUse20250124 tool) {
            return OTHER;
        }

        @Override
        public ToolKind visitTextEditor20241022(BetaToolTextEditor20241022 tool) {
            return OTHER;
        }

        @Override
        public ToolKind visitComputerUse20251124(BetaToolComputerUse20251124 tool) {
            return OTHER;
        }

        @Override
        public ToolKind visitComputerToolset20260801(BetaComputerToolset20260801 tool) {
            return OTHER;
        }

        @Override
        public ToolKind visitTextEditor20250124(BetaToolTextEditor20250124 tool) {
            return OTHER;
        }

        @Override
        public ToolKind visitTextEditor20250429(BetaToolTextEditor20250429 tool) {
            return OTHER;
        }

        @Override
        public ToolKind visitTextEditor20250728(BetaToolTextEditor20250728 tool) {
            return OTHER;
        }

        @Override
        public ToolKind visitWebSearchTool20250305(BetaWebSearchTool20250305 tool) {
            return WEB_SEARCH;
        }

        @Override
        public ToolKind visitWebFetchTool20250910(BetaWebFetchTool20250910 tool) {
            return OTHER;
        }

        @Override
        public ToolKind visitWebSearchTool20260209(BetaWebSearchTool20260209 tool) {
            return WEB_SEARCH;
        }

        @Override
        public ToolKind visitWebFetchTool20260209(BetaWebFetchTool20260209 tool) {
            return OTHER;
        }

        @Override
        public ToolKind visitWebFetchTool20260309(BetaWebFetchTool20260309 tool) {
            return OTHER;
        }

        @Override
        public ToolKind visitWebSearchTool20260318(BetaWebSearchTool20260318 tool) {
            return WEB_SEARCH;
        }

        @Override
        public ToolKind visitWebFetchTool20260318(BetaWebFetchTool20260318 tool) {
            return OTHER;
        }

        @Override
        public ToolKind visitAdvisorTool20260301(BetaAdvisorTool20260301 tool) {
            return OTHER;
        }

        @Override
        public ToolKind visitSearchToolBm25_20251119(BetaToolSearchToolBm25_20251119 tool) {
            return OTHER;
        }

        @Override
        public ToolKind visitSearchToolRegex20251119(BetaToolSearchToolRegex20251119 tool) {
            return OTHER;
        }

        @Override
        public ToolKind visitMcpToolset(BetaMcpToolset tool) {
            return OTHER;
        }

        @Override
        public ToolKind unknown(JsonValue json) {
            return OTHER;
        }
    }
}
