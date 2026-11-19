package com.epam.aidial.provider.upstream;

import com.epam.aidial.provider.api.AdapterRequest;
import java.util.Locale;

/** The message may reach the client: it never holds a credential. */
public final class UpstreamRefusedException extends RuntimeException {

    public enum Kind {
        MISCONFIGURED,
        BAD_GATEWAY
    }

    private final transient Kind kind;

    private UpstreamRefusedException(Kind kind, String message) {
        super(message, null, false, false);
        this.kind = kind;
    }

    public static UpstreamRefusedException misconfigured(String message) {
        return new UpstreamRefusedException(Kind.MISCONFIGURED, message);
    }

    public static UpstreamRefusedException missingEndpoint() {
        return badGateway(missing(AdapterRequest.ENDPOINT_HEADER));
    }

    public static UpstreamRefusedException invalidEndpointFormat() {
        return badGateway("Invalid upstream endpoint format");
    }

    public static UpstreamRefusedException missingKey() {
        return badGateway(missing(AdapterRequest.KEY_HEADER));
    }

    // The wording of the other DIAL adapters.
    private static String missing(String header) {
        return header.toUpperCase(Locale.ROOT) + " header is missing in the request.";
    }

    public static UpstreamRefusedException badGateway(String message) {
        return new UpstreamRefusedException(Kind.BAD_GATEWAY, message);
    }

    public Kind kind() {
        return kind;
    }
}
