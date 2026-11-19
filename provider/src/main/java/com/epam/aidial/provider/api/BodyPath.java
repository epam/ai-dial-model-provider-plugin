package com.epam.aidial.provider.api;

/**
 * The form DIAL Core addresses a body position in: {@code prefix.body} and then the JSON path, e.g. {@code
 * prefix.body.messages[1]}.
 */
public record BodyPath(String value) {

    public static final BodyPath ROOT = new BodyPath("prefix.body");

    public BodyPath member(String name) {
        return new BodyPath(value + "." + name);
    }

    public BodyPath index(int i) {
        return new BodyPath(value + "[" + i + "]");
    }

    @Override
    public String toString() {
        return value;
    }
}
