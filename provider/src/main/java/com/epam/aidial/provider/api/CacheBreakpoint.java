package com.epam.aidial.provider.api;

/**
 * @param path       the body position of the last cached block
 * @param ttlSeconds the TTL of the longest breakpoint
 */
public record CacheBreakpoint(BodyPath path, int ttlSeconds) {}
