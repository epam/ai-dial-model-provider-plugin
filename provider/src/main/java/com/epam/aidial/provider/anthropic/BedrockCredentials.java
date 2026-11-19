package com.epam.aidial.provider.anthropic;

import com.epam.aidial.provider.credentials.AwsCredentials;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;

sealed interface BedrockCredentials {

    record Fixed(AwsCredentials.Provider provider) implements BedrockCredentials {}

    record TaggedPerRequest(TaggedRole role) implements BedrockCredentials {}

    interface TaggedRole {
        Future<AwsCredentials.Provider> provider(MultiMap headers, String model, Future<Void> aborted);
    }
}
