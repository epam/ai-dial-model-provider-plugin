package com.epam.aidial.provider.credentials;

import org.jspecify.annotations.Nullable;

/** {@link #toString} is a mask, so a record or message that holds a secret never shows it. */
public record Secret(String value) {

    public static @Nullable Secret of(@Nullable String value) {
        return value == null ? null : new Secret(value);
    }

    @Override
    public String toString() {
        return "***";
    }
}
