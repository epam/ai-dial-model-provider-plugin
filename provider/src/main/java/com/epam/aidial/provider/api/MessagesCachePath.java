package com.epam.aidial.provider.api;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public sealed interface MessagesCachePath {

    Pattern TOOL = Pattern.compile("^prefix\\.body\\.tools\\[(\\d{1,9})]$");
    Pattern SYSTEM = Pattern.compile("^prefix\\.body\\.system\\[(\\d{1,9})]$");
    Pattern BLOCK = Pattern.compile("^prefix\\.body\\.messages\\[(\\d{1,9})]\\.content\\[(\\d{1,9})]$");

    record Tool(int index) implements MessagesCachePath {
        @Override
        public BodyPath path() {
            return BodyPath.ROOT.member("tools").index(index);
        }
    }

    record SystemBlock(int index) implements MessagesCachePath {
        @Override
        public BodyPath path() {
            return BodyPath.ROOT.member("system").index(index);
        }
    }

    record MessageBlock(int message, int index) implements MessagesCachePath {
        @Override
        public BodyPath path() {
            return BodyPath.ROOT
                    .member("messages")
                    .index(message)
                    .member("content")
                    .index(index);
        }
    }

    BodyPath path();

    default String value() {
        return path().value();
    }

    static Optional<MessagesCachePath> parse(String value) {
        Matcher tool = TOOL.matcher(value);
        if (tool.matches()) {
            return Optional.of(new Tool(Integer.parseInt(tool.group(1))));
        }
        Matcher system = SYSTEM.matcher(value);
        if (system.matches()) {
            return Optional.of(new SystemBlock(Integer.parseInt(system.group(1))));
        }
        Matcher block = BLOCK.matcher(value);
        if (block.matches()) {
            return Optional.of(new MessageBlock(Integer.parseInt(block.group(1)), Integer.parseInt(block.group(2))));
        }
        return Optional.empty();
    }
}
