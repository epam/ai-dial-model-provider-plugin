package com.epam.aidial.provider.credentials;

import java.util.function.Supplier;

/** A value made on first use, by one thread; a failed making is not kept. */
final class Lazy<T> implements Supplier<T> {

    private final Supplier<T> make;
    private volatile T value;

    Lazy(Supplier<T> make) {
        this.make = make;
    }

    @Override
    public T get() {
        T v = value;
        if (v == null) {
            synchronized (this) {
                v = value;
                if (v == null) {
                    v = make.get();
                    value = v;
                }
            }
        }
        return v;
    }

    /** Does not make it: for closing only what was opened. */
    T madeOrNull() {
        return value;
    }
}
