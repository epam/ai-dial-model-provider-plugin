package com.epam.aidial.provider.credentials;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** AWS's SigV4 test suite (aws-sig-v4-test-suite). */
class SigV4Test {

    private static final SigV4.Credentials KEYS =
            new SigV4.Credentials("AKIDEXAMPLE", new Secret("wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"), null);
    private static final Instant WHEN = Instant.parse("2015-08-30T12:36:00Z");

    @Test
    void getVanilla() {
        Map<String, String> signed = SigV4.sign(
                "GET",
                URI.create("https://example.amazonaws.com/"),
                Map.of(),
                new byte[0],
                "us-east-1",
                "service",
                KEYS,
                WHEN);
        assertEquals(
                "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request, SignedHeaders=host;x-amz-content-sha256;x-amz-date, "
                        + "Signature=726c5c4879a6b4ccbbd3b24edbd6b8826d34f87450fbbf4e85546fc7ba9c1642",
                signed.get("Authorization"));
        assertEquals("20150830T123600Z", signed.get("X-Amz-Date"));
        assertEquals("example.amazonaws.com", signed.get("host"));
        assertEquals(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", signed.get("x-amz-content-sha256"));
        assertFalse(signed.containsKey("X-Amz-Security-Token"));
    }

    @Test
    void postVanilla() {
        Map<String, String> signed = SigV4.sign(
                "POST",
                URI.create("https://example.amazonaws.com/"),
                Map.of(),
                new byte[0],
                "us-east-1",
                "service",
                KEYS,
                WHEN);
        assertEquals(
                "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request, SignedHeaders=host;x-amz-content-sha256;x-amz-date, "
                        + "Signature=3ad5e249949a59b862eedd9f1bf1ece4693c3042bf860ef5e3351b8925316f98",
                signed.get("Authorization"));
    }

    @Test
    void queryOrder() {
        Map<String, String> signed = SigV4.sign(
                "GET",
                URI.create("https://example.amazonaws.com/?Param2=value2&Param1=value1"),
                Map.of(),
                new byte[0],
                "us-east-1",
                "service",
                KEYS,
                WHEN);
        assertEquals(
                "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request, SignedHeaders=host;x-amz-content-sha256;x-amz-date, "
                        + "Signature=311c7f58b10b06de8540bb5a27f441ee0609f1d5ad7b191e68d7ea87d90e3d6b",
                signed.get("Authorization"));
    }

    private static String signature(
            String method, String url, Map<String, String> headers, String body, String region, String service) {
        String authorization = SigV4.sign(
                        method,
                        URI.create(url),
                        headers,
                        body.getBytes(StandardCharsets.UTF_8),
                        region,
                        service,
                        KEYS,
                        WHEN)
                .get("Authorization");
        return authorization.substring(authorization.indexOf("SignedHeaders"));
    }

    @Test
    void anEscapedPathIsSignedDoubleEscaped() {
        assertEquals(
                "SignedHeaders=content-type;host;x-amz-content-sha256;x-amz-date, "
                        + "Signature=9738e6b5597ce1eda7f43eb51a6ecd98c0e3909c04a644ee8e28b900631c690f",
                signature(
                        "POST",
                        "https://bedrock-runtime.us-east-1.amazonaws.com/model/us.anthropic.claude-x-v1%3A0/invoke",
                        Map.of("Content-Type", "application/json"),
                        "{\"a\":1}",
                        "us-east-1",
                        "bedrock"));
    }

    @Test
    void thePathIsNotNormalized() {
        assertEquals(
                "SignedHeaders=host;x-amz-content-sha256;x-amz-date, "
                        + "Signature=39f735005155a4b9a865edfd4b8e008873db73d117dd379b36558af1d7155481",
                signature("GET", "https://h.amazonaws.com/a/../b//c/./d", Map.of(), "", "us-east-1", "service"));
    }

    @Test
    void portAndEmptyPathAreSigned() {
        assertEquals(
                "SignedHeaders=host;x-amz-content-sha256;x-amz-date, "
                        + "Signature=5d9c8b1f7e63305f89b86527c49287f60d7d4b889732c4c36a13b9a2cfe47301",
                signature("GET", "https://h.amazonaws.com:8443/x", Map.of(), "", "us-east-1", "service"));
        assertEquals(
                "SignedHeaders=host;x-amz-content-sha256;x-amz-date, "
                        + "Signature=3b4f08de51b6438314b4eb17aca9e38371e219763d59be3c5d2e7806213cc8b1",
                signature(
                        "GET",
                        "https://sts.amazonaws.com?Action=GetCallerIdentity",
                        Map.of(),
                        "",
                        "us-east-1",
                        "service"));
    }

    @Test
    void headerValuesAreCollapsedAndNamesSorted() {
        assertEquals(
                "SignedHeaders=accept;anthropic-beta;host;x-amz-content-sha256;x-amz-date, "
                        + "Signature=cd85dbcc15f103063c0036790cce707b351987a7113b0ded2ca0cc1a897ca943",
                signature(
                        "POST",
                        "https://h.amazonaws.com/x",
                        Map.of("anthropic-beta", "  a,  b\t c ", "Accept", "application/json"),
                        "{}",
                        "us-east-1",
                        "bedrock"));
    }

    @Test
    void contentHashIsAddedAndSigned() {
        Map<String, String> signed = SigV4.sign(
                "POST",
                URI.create("https://bedrock-mantle.us-east-1.api.aws/v1/responses?b=2&a=1"),
                Map.of("Content-Type", "application/json"),
                "{}".getBytes(StandardCharsets.UTF_8),
                "us-east-1",
                "bedrock-mantle",
                KEYS,
                WHEN);
        assertEquals(
                "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a", signed.get("x-amz-content-sha256"));
        assertEquals(
                "SignedHeaders=content-type;host;x-amz-content-sha256;x-amz-date, "
                        + "Signature=134a55a7ef69414b25cf837bfd9d76ee9b072bb9e1ab379523e182dd7de548da",
                signed.get("Authorization")
                        .substring(signed.get("Authorization").indexOf("SignedHeaders")));
    }

    @Test
    void emptyRegionIsSigned() {
        assertEquals(
                "SignedHeaders=host;x-amz-content-sha256;x-amz-date, "
                        + "Signature=23c399ed8b4b46e03a8edbed64067b12a4e5ce13720389bf2ea440705005a8f5",
                signature("GET", "https://h.amazonaws.com/x", Map.of(), "", "", "service"));
    }

    @Test
    void sessionTokenIsSigned() {
        SigV4.Credentials session = new SigV4.Credentials("ASIA", new Secret("s"), new Secret("tok"));
        Map<String, String> signed = SigV4.sign(
                "POST",
                URI.create("https://sts.eu-west-1.amazonaws.com?Action=GetCallerIdentity&Version=2011-06-15"),
                Map.of("Content-Type", "application/json"),
                "{}".getBytes(StandardCharsets.UTF_8),
                "eu-west-1",
                "sts",
                session,
                WHEN);
        assertEquals("tok", signed.get("X-Amz-Security-Token"));
        String authorization = signed.get("Authorization");
        assertEquals(
                "AWS4-HMAC-SHA256 Credential=ASIA/20150830/eu-west-1/sts/aws4_request, SignedHeaders=content-type;host;x-amz-content-sha256;x-amz-date;x-amz-security-token",
                authorization.substring(0, authorization.indexOf(", Signature=")));
        assertFalse(session.toString().contains("tok"));
    }
}
