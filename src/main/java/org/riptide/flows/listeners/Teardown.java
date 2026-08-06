/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.listeners;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs the steps of a {@link Listener#stop()} so that one failing step does not strand the
 * resources released by the steps after it.
 *
 * <p>Netty's {@code syncUninterruptibly()} sneaky-throws the future's cause, checked exceptions
 * included, so any step can throw. Straight-line teardown therefore skips everything below the
 * first failure — which is how a channel that fails to close takes the event loop groups with it.
 *
 * <p>Steps run in the order given; several teardown orderings are load-bearing (a UDP listener must
 * deregister its {@code socketDrops} gauge before releasing the port it describes). Failures are
 * collected and rethrown by {@link #done()}: the first as-is, the rest suppressed onto it. Keeping
 * the first exception rather than wrapping matters because {@code Daemon.stop()} catches
 * {@code Exception} specifically to receive Netty's sneaky-thrown cause intact.
 */
final class Teardown {

    private final List<Throwable> failures = new ArrayList<>();

    /** Runs {@code step}, recording rather than propagating any failure. */
    void attempt(final Runnable step) {
        try {
            step.run();
        } catch (final Throwable t) {
            this.failures.add(t);
        }
    }

    /** As {@link #attempt}, skipping the step when {@code resource} was never acquired. */
    void attemptIfPresent(final Object resource, final Runnable step) {
        if (resource != null) {
            attempt(step);
        }
    }

    /**
     * Rethrows the first recorded failure with the remainder suppressed, or returns if every step
     * succeeded.
     */
    void done() {
        if (this.failures.isEmpty()) {
            return;
        }
        final Throwable first = this.failures.getFirst();
        this.failures.stream().skip(1).forEach(first::addSuppressed);
        throw sneakyThrow(first);
    }

    // Preserves the original throwable's type instead of wrapping it: Daemon.stop() catches
    // Exception (not RuntimeException) precisely so Netty's sneaky-thrown checked causes reach it
    // unaltered, and wrapping here would defeat that.
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneakyThrow(final Throwable t) throws T {
        throw (T) t;
    }
}
