/*
 * Copyright © 2026 Hitesh Soliwal and Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.chromellm.standalone;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/** One monotonic budget shared by all inference and tool work in a run. */
final class AgentDeadline {
    private final long started;
    private final long budget;

    private AgentDeadline(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("timeout must be in (0, 1 hour]");
        }
        try { budget = timeout.toNanos(); }
        catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("timeout is too large", overflow);
        }
        started = System.nanoTime();
    }

    static AgentDeadline start(Duration timeout) { return new AgentDeadline(timeout); }

    void check() throws InterruptedException, TimeoutException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Agent interrupted");
        }
        if (System.nanoTime() - started >= budget) throw new TimeoutException("Agent deadline exceeded");
    }

    Duration remaining() throws InterruptedException, TimeoutException {
        check();
        long nanos = budget - (System.nanoTime() - started);
        if (nanos <= 0) throw new TimeoutException("Agent deadline exceeded");
        return Duration.ofNanos(nanos);
    }
}
