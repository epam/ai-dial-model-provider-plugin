package com.epam.aidial.provider.api;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public sealed interface ResponsesCachePath {

    Pattern TOOL = Pattern.compile("^prefix\\.body\\.tools\\[(\\d{1,9})]$");
    Pattern INSTRUCTIONS = Pattern.compile("^prefix\\.body\\.instructions\\[0]$");
    Pattern INPUT = Pattern.compile("^prefix\\.body\\.input\\[(\\d{1,9})]$");

    record Tool(int index) implements ResponsesCachePath {
        @Override
        public BodyPath path() {
            return BodyPath.ROOT.member("tools").index(index);
        }
    }

    record Instructions() implements ResponsesCachePath {
        @Override
        public BodyPath path() {
            return BodyPath.ROOT.member("instructions").index(0);
        }
    }

    record InputItem(int index) implements ResponsesCachePath {
        @Override
        public BodyPath path() {
            return BodyPath.ROOT.member("input").index(index);
        }
    }

    BodyPath path();

    default String value() {
        return path().value();
    }

    static Optional<ResponsesCachePath> parse(String value) {
        Matcher tool = TOOL.matcher(value);
        if (tool.matches()) {
            return Optional.of(new Tool(Integer.parseInt(tool.group(1))));
        }
        if (INSTRUCTIONS.matcher(value).matches()) {
            return Optional.of(new Instructions());
        }
        Matcher input = INPUT.matcher(value);
        if (input.matches()) {
            return Optional.of(new InputItem(Integer.parseInt(input.group(1))));
        }
        return Optional.empty();
    }
}
