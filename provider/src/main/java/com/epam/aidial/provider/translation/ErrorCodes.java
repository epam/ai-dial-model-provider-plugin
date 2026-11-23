package com.epam.aidial.provider.translation;

import java.util.Set;

/** What the Anthropic API calls a failure, as the Anthropic clients read it, and what the Responses API's codes are. */
final class ErrorCodes {

    /** The error codes of the Responses API, and the error types DIAL writes into a stream, that blame the request. */
    private static final Set<String> REQUEST_FAULTS = Set.of(
            "invalid_request_error",
            "context_length_exceeded",
            "invalid_prompt",
            "bio_policy",
            "invalid_image",
            "invalid_image_format",
            "invalid_base64_image",
            "invalid_image_url",
            "invalid_image_mode",
            "image_too_large",
            "image_too_small",
            "image_parse_error",
            "image_content_policy_violation",
            "image_file_too_large",
            "image_file_not_found",
            "unsupported_image_media_type",
            "empty_image_file",
            "failed_to_download_image");

    private ErrorCodes() {}

    /** The HTTP status of a failed response's error code: 429, 504, 400 for a fault of the request, else 500. */
    static int status(String code) {
        if (code.equals("rate_limit_exceeded")) {
            return 429;
        }
        if (code.equals("vector_store_timeout")) {
            return 504;
        }
        return REQUEST_FAULTS.contains(code) ? 400 : 500;
    }

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
