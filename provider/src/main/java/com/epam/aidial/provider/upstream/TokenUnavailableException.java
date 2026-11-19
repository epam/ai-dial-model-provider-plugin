package com.epam.aidial.provider.upstream;

public abstract class TokenUnavailableException extends RuntimeException {

    protected TokenUnavailableException(String message, Throwable cause) {
        super(message, cause, false, false);
    }
}
