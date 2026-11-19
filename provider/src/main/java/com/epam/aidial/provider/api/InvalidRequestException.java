package com.epam.aidial.provider.api;

/**
 * Each API answers it as its own invalid request error (a 400). Thrown where the faulty part is read, so that nothing
 * is done for a request that is refused.
 */
public final class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message, null, false, false);
    }
}
