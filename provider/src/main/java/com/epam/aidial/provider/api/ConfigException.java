package com.epam.aidial.provider.api;

/** The message starts with the name of the setting, {@link #setting()}. */
public final class ConfigException extends RuntimeException {

    private final String setting;

    public ConfigException(String setting, String message) {
        this(setting, message, null);
    }

    public ConfigException(String setting, String message, Throwable cause) {
        super(message, cause);
        this.setting = setting;
    }

    public String setting() {
        return setting;
    }
}
