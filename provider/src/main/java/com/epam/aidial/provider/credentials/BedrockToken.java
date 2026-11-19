package com.epam.aidial.provider.credentials;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * Mirrors the AWS Bedrock token generator: a CallWithBearerToken URL presigned for 12 h, scheme stripped, base64. The
 * empty body is hashed, not UNSIGNED-PAYLOAD.
 */
public final class BedrockToken {

    private static final URI CALL = URI.create("https://bedrock.amazonaws.com/?Action=CallWithBearerToken");

    private BedrockToken() {}

    public static String token(SigV4.Credentials credentials, String region, Instant now) {
        String query = SigV4.presignedQuery("POST", CALL, region, "bedrock", credentials, now, 43_200);
        String url = "bedrock.amazonaws.com/?" + query + "&Version=1";
        return "bedrock-api-key-" + Base64.getEncoder().encodeToString(url.getBytes(StandardCharsets.UTF_8));
    }
}
