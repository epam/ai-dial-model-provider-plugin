package com.epam.aidial.provider;

import static com.epam.aidial.provider.testing.MockUpstream.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.epam.aidial.provider.api.ConfigException;
import com.epam.aidial.provider.core.Config;
import io.vertx.core.Vertx;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ModelProvidersTest {

    private static final String USER_INFO_TAGS = "{\"project\":\"UserInfo.project\"}";
    private static final String MODEL_TAGS = "{\"model\":\"Bedrock.modelId\"}";

    private Vertx vertx;

    @BeforeEach
    void start() {
        vertx = Vertx.vertx();
    }

    @AfterEach
    void stop() throws Exception {
        await(vertx.close());
    }

    @Test
    void standaloneRequiresDialUrlForUserInfoTags() {
        ConfigException e = assertThrows(
                ConfigException.class,
                () -> ModelProviders.standalone(vertx, Config.from(Map.of("AWS_SESSION_TAGS", USER_INFO_TAGS))));
        assertEquals("DIAL_URL must be set when AWS_SESSION_TAGS has a UserInfo source", e.getMessage());
        assertEquals("DIAL_URL", e.setting());
    }

    @Test
    void standaloneStartsWithoutDialUrlWhenNoTagNeedsUserInfo() throws Exception {
        await(ModelProviders.standalone(vertx, Config.from(Map.of())).close());
        await(ModelProviders.standalone(vertx, Config.from(Map.of("AWS_SESSION_TAGS", MODEL_TAGS)))
                .close());
    }

    @Test
    void standaloneStartsWithDialUrl() throws Exception {
        Map<String, String> settings = Map.of("AWS_SESSION_TAGS", USER_INFO_TAGS, "DIAL_URL", "http://127.0.0.1:9");
        await(ModelProviders.standalone(vertx, Config.from(settings)).close());
    }

    @Test
    void pluginStartsWithoutDialUrl() throws Exception {
        await(ModelProviders.create(vertx, Config.from(Map.of("AWS_SESSION_TAGS", USER_INFO_TAGS)))
                .close());
    }
}
