package com.epam.aidial.provider.credentials;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.epam.aidial.provider.core.Config;
import com.epam.aidial.provider.json.Json;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AwsCredentialSettingsTest {

    private static final AwsCredentialSettings ENV = AwsCredentialSettings.of(Config.from(Map.of(
            "AWS_ACCESS_KEY_ID", "AKID",
            "AWS_SECRET_ACCESS_KEY", "S",
            "AWS_SESSION_TOKEN", "T",
            "AWS_ASSUME_ROLE_ARN", "arn:role")));

    private static AwsExtraData extra(String json) throws Exception {
        return (AwsExtraData) Json.binder(AwsExtraData.class).readValue(json);
    }

    @Test
    void keysWinOverTheRole() {
        assertEquals(
                new AwsCredentials.CredentialSet("AKID", new Secret("S"), new Secret("T"), ""), ENV.credentialSet());
    }

    @Test
    void absentMembersKeepTheSettingAndStringsReplaceIt() throws Exception {
        assertEquals(ENV, ENV.overlay(extra("{}")));
        assertEquals(
                new AwsCredentialSettings("AKID2", "S", "T", "arn:role"),
                ENV.overlay(extra("{\"aws_access_key_id\":\"AKID2\"}")));
    }

    @Test
    void nullAndEmptyClearTheSetting() throws Exception {
        AwsCredentialSettings cleared = ENV.overlay(
                extra("""
                        {"aws_access_key_id":null,"aws_secret_access_key":""}"""));
        assertEquals(new AwsCredentialSettings("", "", "T", "arn:role"), cleared);
        assertEquals(
                new AwsCredentials.CredentialSet("", new Secret(""), new Secret(""), "arn:role"),
                cleared.credentialSet(),
                "keys that are not a pair are left out, and the role is taken");
    }

    @Test
    void sessionTokenWithoutKeysIsLeftOut() throws Exception {
        AwsCredentialSettings noKeys = new AwsCredentialSettings("", "", "T", "");
        assertEquals(new AwsCredentials.CredentialSet("", new Secret(""), new Secret(""), ""), noKeys.credentialSet());
    }
}
