package com.agenticfx.core.port;

import java.util.function.Supplier;

/** Transaction boundary. Each call runs in its own transaction and commits when the supplier returns. */
public interface Transactions {

    <T> T inTransaction(Supplier<T> work);

    default void run(Runnable work) {
        inTransaction(() -> {
            work.run();
            return null;
        });
    }
}
