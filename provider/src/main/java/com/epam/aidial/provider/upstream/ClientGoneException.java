package com.epam.aidial.provider.upstream;

/** Nothing is written or logged for it: the client is gone. */
public final class ClientGoneException extends RuntimeException {

    public ClientGoneException() {
        super("client gone", null, false, false);
    }
}
