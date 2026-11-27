package com.epam.aidial.provider.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.epam.aidial.provider.api.AdapterRequest;
import com.epam.aidial.provider.testing.Requests;
import com.epam.aidial.provider.upstream.UpstreamRefusedException;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UpstreamExtraDataTest {

    private static AdapterRequest request(String extraData) {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        if (extraData != null) {
            headers.set("x-upstream-extra-data", extraData);
        }
        return Requests.of(
                HttpMethod.POST,
                "/anthropic/v1/messages",
                null,
                headers,
                Buffer.buffer(),
                Promise.<Void>promise().future());
    }

    @Test
    void absentOrEmptyHeaderHasNoMembers() throws Exception {
        for (String header : new String[] {null, ""}) {
            UpstreamExtraData.Vertex vertex = UpstreamExtraData.Vertex.of(request(header));
            assertEquals("d", vertex.regionOr("d"));
            assertEquals("d", vertex.projectOr("d"));
            UpstreamExtraData.Bedrock bedrock = UpstreamExtraData.Bedrock.of(request(header));
            assertNull(bedrock.region());
            assertNull(bedrock.aws().accessKeyId());
        }
    }

    @Test
    void vertexTakesTheStringsAndLeavesTheEmptyAndNullToTheDefaults() throws Exception {
        UpstreamExtraData.Vertex vertex = UpstreamExtraData.Vertex.of(
                request("""
                        {"region":"europe-west1","project":"é","n":1}"""));
        assertEquals("europe-west1", vertex.regionOr("d"));
        assertEquals("é", vertex.projectOr("d"));
        for (String empty : List.of("{}", "{\"region\":null,\"project\":\"\"}")) {
            UpstreamExtraData.Vertex v = UpstreamExtraData.Vertex.of(request(empty));
            assertEquals("d", v.regionOr("d"), empty);
            assertEquals("d", v.projectOr("d"), empty);
        }
    }

    @Test
    void bedrockKeepsAbsentNullAndEmptyApart() throws Exception {
        UpstreamExtraData.Bedrock bedrock = UpstreamExtraData.Bedrock.of(
                request(
                        """
                        {"region":"","claude_client":"mantle","aws_access_key_id":null,"aws_secret_access_key":"s","aws_assume_role_arn":""}"""));
        assertEquals("", bedrock.region());
        assertEquals("mantle", bedrock.claudeClient());
        assertEquals("", bedrock.aws().accessKeyId(), "a null clears the setting");
        assertEquals("s", bedrock.aws().secretAccessKey());
        assertNull(bedrock.aws().sessionToken(), "absent keeps the setting");
        assertEquals("", bedrock.aws().assumeRoleArn());
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {"[]", "\"s\"", "1", "null", "nul", "{", "{} x"})
    void headerThatIsNotAnObjectIsRefused(String bad) {
        assertThrows(UpstreamRefusedException.class, () -> UpstreamExtraData.Vertex.of(request(bad)));
        assertThrows(UpstreamRefusedException.class, () -> UpstreamExtraData.Bedrock.of(request(bad)));
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(
            strings = {"{\"region\":1}", "{\"region\":true}", "{\"region\":[]}", "{\"project\":{}}", "{\"project\":0}"})
    void vertexSettingThatIsNotStringIsRefused(String bad) {
        assertThrows(UpstreamRefusedException.class, () -> UpstreamExtraData.Vertex.of(request(bad)));
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(
            strings = {
                "{\"region\":null}",
                "{\"claude_client\":null}",
                "{\"region\":1}",
                "{\"aws_access_key_id\":1}",
                "{\"aws_session_token\":false}"
            })
    void bedrockSettingThatIsNotAllowedIsRefused(String bad) {
        assertThrows(UpstreamRefusedException.class, () -> UpstreamExtraData.Bedrock.of(request(bad)));
    }
}
