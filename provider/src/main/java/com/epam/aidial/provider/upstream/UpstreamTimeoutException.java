package com.epam.aidial.provider.upstream;

public final class UpstreamTimeoutException extends RuntimeException {

    public UpstreamTimeoutException() {
        super("Timeout on reading data from socket", null, false, false);
    }
}
