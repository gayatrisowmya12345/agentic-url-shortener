package com.linkforge.service.retry;

/**
 * Functional abstraction for backoff delays to enable deterministic, fast testing without real sleeps.
 */
@FunctionalInterface
public interface Sleeper {

    void sleep(long millis) throws InterruptedException;

    Sleeper SYSTEM = Thread::sleep;
    Sleeper NO_OP = millis -> {};
}
