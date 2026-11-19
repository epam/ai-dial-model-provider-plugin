package com.epam.aidial.provider.upstream;

public final class PoolExhaustedException extends RuntimeException {

    public PoolExhaustedException() {
        super("no free upstream connection within the pool timeout", null, false, false);
    }
}
