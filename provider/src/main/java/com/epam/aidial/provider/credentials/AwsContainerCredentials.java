package com.epam.aidial.provider.credentials;

import com.epam.aidial.provider.core.Config;
import com.google.auth.oauth2.AwsCredentials;
import com.google.auth.oauth2.AwsSecurityCredentials;
import com.google.auth.oauth2.AwsSecurityCredentialsSupplier;
import com.google.auth.oauth2.ExternalAccountSupplierContext;
import com.google.auth.oauth2.GoogleCredentials;
import java.io.IOException;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.ContainerCredentialsProvider;
import software.amazon.awssdk.core.SdkSystemSetting;
import software.amazon.awssdk.core.exception.SdkException;

/**
 * The container's AWS credentials (an ECS task role, EKS Pod Identity) for google-auth's AWS external account. The AWS
 * SDK's {@link ContainerCredentialsProvider} reads AWS_CONTAINER_* from the environment (or the matching system
 * properties), not from {@link Config}, which supplies only the region.
 */
final class AwsContainerCredentials implements AwsSecurityCredentialsSupplier {

    private static final long serialVersionUID = 1L;

    private final transient Config config;
    private final transient ContainerCredentialsProvider container = ContainerCredentialsProvider.create();

    private AwsContainerCredentials(Config config) {
        this.config = config;
    }

    static GoogleCredentials replacingSourceOf(GoogleCredentials credentials, Config config) {
        if (!containerUrlSet() || !(credentials instanceof AwsCredentials aws)) {
            return credentials;
        }
        return AwsCredentials.newBuilder(aws)
                .setCredentialSource(null)
                .setAwsSecurityCredentialsSupplier(new AwsContainerCredentials(config))
                .build();
    }

    private static boolean containerUrlSet() {
        return SdkSystemSetting.AWS_CONTAINER_CREDENTIALS_FULL_URI
                        .getStringValue()
                        .filter(url -> !url.isEmpty())
                        .isPresent()
                || SdkSystemSetting.AWS_CONTAINER_CREDENTIALS_RELATIVE_URI
                        .getStringValue()
                        .isPresent();
    }

    @Override
    public String getRegion(ExternalAccountSupplierContext context) {
        return config.awsRegion();
    }

    @Override
    public AwsSecurityCredentials getCredentials(ExternalAccountSupplierContext context) throws IOException {
        try {
            var credentials = container.resolveCredentials();
            String session = credentials instanceof AwsSessionCredentials s ? s.sessionToken() : null;
            return new AwsSecurityCredentials(credentials.accessKeyId(), credentials.secretAccessKey(), session);
        } catch (SdkException e) {
            throw new IOException("container credentials request: " + e.getMessage(), e);
        }
    }
}
