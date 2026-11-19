package com.epam.aidial.provider.credentials;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import software.amazon.awssdk.http.ContentStreamProvider;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4FamilyHttpSigner.AuthLocation;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.SignedRequest;
import software.amazon.awssdk.identity.spi.AwsCredentialsIdentity;
import software.amazon.awssdk.identity.spi.AwsSessionCredentialsIdentity;

/**
 * The path is quoted once more (an already escaped path is signed double-escaped) and not normalized: AWS's rule for a
 * service other than S3.
 */
public final class SigV4 {

    private static final AwsV4HttpSigner SIGNER = AwsV4HttpSigner.create();

    private SigV4() {}

    /** The session token is null for long-term keys. */
    public record Credentials(String accessKeyId, Secret secretAccessKey, @Nullable Secret sessionToken) {

        private AwsCredentialsIdentity toSdk() {
            return sessionToken == null
                    ? AwsCredentialsIdentity.create(accessKeyId, secretAccessKey.value())
                    : AwsSessionCredentialsIdentity.create(accessKeyId, secretAccessKey.value(), sessionToken.value());
        }
    }

    public static Map<String, String> sign(
            String method,
            URI uri,
            Map<String, String> headers,
            byte[] payload,
            String region,
            String service,
            Credentials credentials,
            Instant now) {
        SdkHttpRequest.Builder request = request(method, uri);
        headers.forEach(request::putHeader);
        SignedRequest signed = SIGNER.sign(r -> r.identity(credentials.toSdk())
                .request(request.build())
                .payload(() -> new ByteArrayInputStream(payload)) // fromByteArray would copy the body
                .putProperty(AwsV4HttpSigner.REGION_NAME, region)
                .putProperty(AwsV4HttpSigner.SERVICE_SIGNING_NAME, service)
                .putProperty(AwsV4HttpSigner.DOUBLE_URL_ENCODE, true)
                .putProperty(AwsV4HttpSigner.NORMALIZE_PATH, false)
                .putProperty(AwsV4HttpSigner.SIGNING_CLOCK, Clock.fixed(now, ZoneOffset.UTC)));
        Map<String, String> out = new LinkedHashMap<>();
        signed.request().firstMatchingHeader("Host").ifPresent(value -> out.put("host", value));
        for (String name : List.of("X-Amz-Date", "X-Amz-Security-Token", "x-amz-content-sha256", "Authorization")) {
            signed.request().firstMatchingHeader(name).ifPresent(value -> out.put(name, value));
        }
        return out;
    }

    /** Signed over the empty body's hash; the parameters come in the signer's order. */
    public static String presignedQuery(
            String method,
            URI uri,
            String region,
            String service,
            Credentials credentials,
            Instant now,
            long expiresSeconds) {
        SdkHttpRequest request = request(method, uri).build();
        SignedRequest signed = SIGNER.sign(r -> r.identity(credentials.toSdk())
                .request(request)
                .payload(ContentStreamProvider.fromByteArray(new byte[0]))
                .putProperty(AwsV4HttpSigner.REGION_NAME, region)
                .putProperty(AwsV4HttpSigner.SERVICE_SIGNING_NAME, service)
                .putProperty(AwsV4HttpSigner.DOUBLE_URL_ENCODE, true)
                .putProperty(AwsV4HttpSigner.NORMALIZE_PATH, false)
                .putProperty(AwsV4HttpSigner.AUTH_LOCATION, AuthLocation.QUERY_STRING)
                .putProperty(AwsV4HttpSigner.EXPIRATION_DURATION, Duration.ofSeconds(expiresSeconds))
                .putProperty(AwsV4HttpSigner.SIGNING_CLOCK, Clock.fixed(now, ZoneOffset.UTC)));
        return signed.request().encodedQueryParameters().orElseThrow();
    }

    private static SdkHttpRequest.Builder request(String method, URI uri) {
        return SdkHttpRequest.builder().method(SdkHttpMethod.fromValue(method)).uri(uri);
    }
}
