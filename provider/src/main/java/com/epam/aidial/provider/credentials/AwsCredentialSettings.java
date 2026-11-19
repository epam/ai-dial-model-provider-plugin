package com.epam.aidial.provider.credentials;

import com.epam.aidial.provider.core.Config;
import org.jspecify.annotations.Nullable;

public record AwsCredentialSettings(
        String accessKeyId, String secretAccessKey, String sessionToken, String assumeRoleArn) {

    public static AwsCredentialSettings of(Config config) {
        return new AwsCredentialSettings(
                config.awsAccessKeyId(),
                config.awsSecretAccessKey(),
                config.awsSessionToken(),
                config.awsAssumeRoleArn());
    }

    /** A present member replaces the setting; a JSON null or an empty string clears it. */
    public AwsCredentialSettings overlay(AwsExtraData extra) {
        return new AwsCredentialSettings(
                or(extra.accessKeyId(), accessKeyId),
                or(extra.secretAccessKey(), secretAccessKey),
                or(extra.sessionToken(), sessionToken),
                or(extra.assumeRoleArn(), assumeRoleArn));
    }

    private static String or(@Nullable String member, String current) {
        return member == null ? current : member;
    }

    public AwsCredentials.CredentialSet credentialSet() {
        if (accessKeyId.isEmpty() || secretAccessKey.isEmpty()) {
            return new AwsCredentials.CredentialSet("", new Secret(""), new Secret(""), assumeRoleArn);
        }
        return new AwsCredentials.CredentialSet(accessKeyId, new Secret(secretAccessKey), new Secret(sessionToken), "");
    }
}
