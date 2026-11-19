package com.epam.aidial.provider.upstream;

import com.google.common.base.CharMatcher;

public final class Urls {

    private static final CharMatcher SLASH = CharMatcher.is('/');

    private Urls() {}

    public static String trimTrailingSlashes(String url) {
        return SLASH.trimTrailingFrom(url);
    }
}
