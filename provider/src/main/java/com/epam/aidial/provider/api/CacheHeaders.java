package com.epam.aidial.provider.api;

import io.vertx.core.MultiMap;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CacheHeaders {

    private static final Logger log = LoggerFactory.getLogger(CacheHeaders.class);

    public static final String BREAKPOINT = "X-Dial-Cache-Breakpoint-Path";

    public static final String EXPIRE_AT = "X-Dial-Cache-Expire-At";

    private static final String PREFIX = "x-dial-cache-";

    private CacheHeaders() {}

    public static boolean asked(MultiMap requestHeaders) {
        return requestHeaders.contains(BREAKPOINT);
    }

    public static void stamp(MultiMap answerHeaders, CacheBreakpoint breakpoint, Instant now) {
        answerHeaders.set(BREAKPOINT, breakpoint.path().value());
        answerHeaders.set(EXPIRE_AT, Long.toString(now.getEpochSecond() + breakpoint.ttlSeconds()));
    }

    public static boolean isCacheHeader(String name) {
        return name.toLowerCase(Locale.ROOT).startsWith(PREFIX);
    }

    public static final class MessagesCacheHeaders {

        private final int tools;
        private final int systemBlocks;
        private final boolean hasInstructions;
        private final List<int[]> itemOfBlock;

        public MessagesCacheHeaders(int tools, int systemBlocks, boolean hasInstructions, List<int[]> itemOfBlock) {
            this.tools = tools;
            this.systemBlocks = systemBlocks;
            this.hasInstructions = hasInstructions;
            this.itemOfBlock = itemOfBlock;
        }

        public void toUpstream(MultiMap headers) {
            String value = headers.get(BREAKPOINT);
            headers.remove(BREAKPOINT);
            if (value == null) {
                return;
            }
            Optional<ResponsesCachePath> path = MessagesCachePath.parse(value).flatMap(this::toResponses);
            if (path.isPresent()) {
                headers.set(BREAKPOINT, path.get().value());
            } else {
                log.warn(
                        "Dropping the cache breakpoint: no element of the Responses request it is translated into: {}",
                        value);
            }
        }

        public void toClient(MultiMap upstreamHeaders, MultiMap clientHeaders) {
            String value = upstreamHeaders.get(BREAKPOINT);
            if (value == null) {
                return;
            }
            Optional<MessagesCachePath> path = ResponsesCachePath.parse(value).flatMap(this::toMessages);
            if (path.isEmpty()) {
                log.warn(
                        "Dropping the cache breakpoint: no block of the Messages request it is translated into: {}",
                        value);
                return;
            }
            clientHeaders.set(BREAKPOINT, path.get().value());
            String expireAt = upstreamHeaders.get(EXPIRE_AT);
            if (expireAt != null) {
                clientHeaders.set(EXPIRE_AT, expireAt);
            }
        }

        public Optional<ResponsesCachePath> toResponses(MessagesCachePath path) {
            return switch (path) {
                case MessagesCachePath.Tool tool ->
                    tool.index() < tools ? Optional.of(new ResponsesCachePath.Tool(tool.index())) : Optional.empty();
                case MessagesCachePath.SystemBlock system ->
                    system.index() < systemBlocks && hasInstructions
                            ? Optional.of(new ResponsesCachePath.Instructions())
                            : Optional.empty();
                case MessagesCachePath.MessageBlock block -> blockToResponses(block);
            };
        }

        private Optional<ResponsesCachePath> blockToResponses(MessagesCachePath.MessageBlock block) {
            if (block.message() >= itemOfBlock.size() || block.index() >= itemOfBlock.get(block.message()).length) {
                return Optional.empty();
            }
            int item = nearestKeptItem(block.message(), block.index());
            if (item >= 0) {
                return Optional.of(new ResponsesCachePath.InputItem(item));
            }
            if (hasInstructions) {
                return Optional.of(new ResponsesCachePath.Instructions());
            }
            return tools > 0 ? Optional.of(new ResponsesCachePath.Tool(tools - 1)) : Optional.empty();
        }

        private int nearestKeptItem(int message, int block) {
            for (int j = message; j >= 0; j--) {
                int[] blocks = itemOfBlock.get(j);
                for (int k = j == message ? block : blocks.length - 1; k >= 0; k--) {
                    if (blocks[k] >= 0) {
                        return blocks[k];
                    }
                }
            }
            return -1;
        }

        public Optional<MessagesCachePath> toMessages(ResponsesCachePath path) {
            return switch (path) {
                case ResponsesCachePath.Tool tool ->
                    tool.index() < tools ? Optional.of(new MessagesCachePath.Tool(tool.index())) : Optional.empty();
                case ResponsesCachePath.Instructions() ->
                    hasInstructions && systemBlocks > 0
                            ? Optional.of(new MessagesCachePath.SystemBlock(systemBlocks - 1))
                            : Optional.empty();
                case ResponsesCachePath.InputItem item -> itemToMessages(item);
            };
        }

        private Optional<MessagesCachePath> itemToMessages(ResponsesCachePath.InputItem item) {
            for (int j = itemOfBlock.size() - 1; j >= 0; j--) {
                int[] blocks = itemOfBlock.get(j);
                for (int k = blocks.length - 1; k >= 0; k--) {
                    if (blocks[k] == item.index()) {
                        return Optional.of(new MessagesCachePath.MessageBlock(j, k));
                    }
                }
            }
            return Optional.empty();
        }
    }
}
