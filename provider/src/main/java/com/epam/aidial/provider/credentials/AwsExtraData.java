package com.epam.aidial.provider.credentials;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import org.jspecify.annotations.Nullable;

/**
 * An absent member is null, a JSON null is the empty string, which clears a setting; a member that is no string is
 * refused. Bound by its fields, not as a record: a record's absent member would take the empty string too.
 */
public final class AwsExtraData {

    public static final AwsExtraData NONE = new AwsExtraData();

    @JsonProperty("aws_access_key_id")
    @JsonSetter(nulls = Nulls.AS_EMPTY)
    private @Nullable String accessKeyId;

    @JsonProperty("aws_secret_access_key")
    @JsonSetter(nulls = Nulls.AS_EMPTY)
    private @Nullable String secretAccessKey;

    @JsonProperty("aws_session_token")
    @JsonSetter(nulls = Nulls.AS_EMPTY)
    private @Nullable String sessionToken;

    @JsonProperty("aws_assume_role_arn")
    @JsonSetter(nulls = Nulls.AS_EMPTY)
    private @Nullable String assumeRoleArn;

    public @Nullable String accessKeyId() {
        return accessKeyId;
    }

    public @Nullable String secretAccessKey() {
        return secretAccessKey;
    }

    public @Nullable String sessionToken() {
        return sessionToken;
    }

    public @Nullable String assumeRoleArn() {
        return assumeRoleArn;
    }
}
