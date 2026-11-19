package com.epam.aidial.provider.anthropic;

/** The paths are below an upstream's base URL, and below {@code /anthropic} here. */
public enum Endpoint {
    MESSAGES("/v1/messages"),
    COUNT_TOKENS("/v1/messages/count_tokens");

    private final String path;

    Endpoint(String path) {
        this.path = path;
    }

    String path() {
        return path;
    }
}
