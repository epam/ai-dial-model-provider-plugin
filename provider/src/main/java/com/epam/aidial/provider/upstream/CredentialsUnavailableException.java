package com.epam.aidial.provider.upstream;

public final class CredentialsUnavailableException extends RuntimeException {

    public enum Source {
        SESSION_TAGS,
        AWS_ROLE,
        AWS_CHAIN,
        BEDROCK_TOKEN
    }

    private final transient Source source;

    public CredentialsUnavailableException(Source source, Throwable cause) {
        super(cause.getMessage(), cause, false, false);
        this.source = source;
    }

    public CredentialsUnavailableException(Source source, String message) {
        super(message, null, false, false);
        this.source = source;
    }

    public Source source() {
        return source;
    }
}
