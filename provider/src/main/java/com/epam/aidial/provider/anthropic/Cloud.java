package com.epam.aidial.provider.anthropic;

import java.util.Set;

public enum Cloud {
    PLATFORM(Set.of(), Set.of()),
    GCP(
            Set.of("thinking-token-count-2026-05-13", "prompt-caching-scope-2026-01-05", Cloud.ADVISOR_BETA),
            Set.of("advisor")),
    AZURE(Set.of(Cloud.ADVISOR_BETA), Set.of("advisor")),
    // "Web search is not available on Amazon Bedrock."
    AWS(
            Set.of(
                    "oauth-2025-04-20",
                    "redact-thinking-2026-02-12",
                    "thinking-token-count-2026-05-13",
                    "prompt-caching-scope-2026-01-05",
                    "claude-code-20250219",
                    "advanced-tool-use-2025-11-20",
                    Cloud.ADVISOR_BETA),
            Set.of("advisor", "web_search"));

    private static final String ADVISOR_BETA = "advisor-tool-2026-03-01";

    /** The advisor tool is only usable with its beta flag, so a cloud without the flag drops the tool too. */
    final Set<String> unsupportedBetas;

    final Set<String> unsupportedTools;

    Cloud(Set<String> unsupportedBetas, Set<String> unsupportedTools) {
        this.unsupportedBetas = unsupportedBetas;
        this.unsupportedTools = unsupportedTools;
    }
}
