package com.epam.aidial.provider.translation;

/**
 * The upstream's answer is not what its API says (not JSON, or a field the translator reads is missing or mistyped):
 * the client gets a 500, or an {@code error} event in a stream. The message is for the log, never for the client.
 */
public final class UpstreamResponseException extends RuntimeException {

    public UpstreamResponseException(String message, Throwable cause) {
        super(message, cause);
    }
}
