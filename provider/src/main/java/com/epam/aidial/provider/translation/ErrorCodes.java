package com.epam.aidial.provider.translation;

/** What the Anthropic API calls a failure, as the Anthropic clients read it. */
final class ErrorCodes {

    private ErrorCodes() {}

    /**
     * The {@code error.type} of an HTTP status. The other 4xx statuses are an invalid request, as Anthropic answers
     * them; any other status is an API error.
     */
    static String anthropicType(int status) {
        return switch (status) {
            case 400 -> "invalid_request_error";
            case 401 -> "authentication_error";
            case 402 -> "billing_error";
            case 403 -> "permission_error";
            case 404 -> "not_found_error";
            case 409 -> "conflict_error";
            case 413 -> "request_too_large";
            case 429 -> "rate_limit_error";
            case 504 -> "timeout_error";
            case 503, 529 -> "overloaded_error";
            default -> status >= 400 && status < 500 ? "invalid_request_error" : "api_error";
        };
    }
}
