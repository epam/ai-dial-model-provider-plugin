package com.epam.aidial.provider.upstream;

import com.epam.aidial.provider.api.AdapterResponse;

public interface AdapterErrors {

    String AUTHENTICATION_FAILED = "Authentication failed";
    String INTERNAL_ERROR = "Internal server error";
    String AWS_ROLE_UNAVAILABLE = "Failed to assume the configured AWS role";
    String AWS_CREDENTIALS_UNAVAILABLE = "Failed to resolve AWS credentials for Bedrock. Verify your AWS profile, "
            + "environment variables, or runtime identity configuration and try again.";
    String POOL_EXHAUSTED = "No free upstream connection: the adapter connection pool is exhausted";
    String TIMEOUT = "Request timed out";

    /** The message goes to the client. */
    AdapterResponse invalidRequest(String message);

    AdapterResponse tokenUnavailable();

    /** Plain text, for failures no error handler of the API takes (session tags, bearer token). */
    default AdapterResponse plainInternalError() {
        return AdapterResponse.internalServerError();
    }

    AdapterResponse misconfigured(String message);

    AdapterResponse badGateway(String message);

    AdapterResponse awsRoleUnavailable();

    AdapterResponse awsCredentialsUnavailable();

    /** A 503, which DIAL Core retries on another upstream. */
    AdapterResponse poolExhausted();

    AdapterResponse timeout();

    AdapterResponse internalError();

    AdapterResponse connectionFailed();
}
