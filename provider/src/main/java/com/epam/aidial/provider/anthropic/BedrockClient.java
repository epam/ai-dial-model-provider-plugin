package com.epam.aidial.provider.anthropic;

import java.util.Optional;

enum BedrockClient {
    LEGACY("legacy", "bedrock"),

    MANTLE("mantle", "bedrock-mantle");

    private final String setting;
    private final String service;

    BedrockClient(String setting, String service) {
        this.setting = setting;
        this.service = service;
    }

    public String setting() {
        return setting;
    }

    public String service() {
        return service;
    }

    static Optional<BedrockClient> fromSetting(String setting) {
        for (BedrockClient client : values()) {
            if (client.setting.equals(setting)) {
                return Optional.of(client);
            }
        }
        return Optional.empty();
    }
}
