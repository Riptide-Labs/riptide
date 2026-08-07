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
 * the first throwable rather than wrapping matters because the caller diagnoses from it —
 * {@code Daemon.stop()} logs it against the listener's name — and wrapping would bury Netty's
 * sneaky-thrown cause under a synthesised type.
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
    // Identity, not equality, is the point: addSuppressed throws IllegalArgumentException when
    // handed the very object it is called on, and two distinct throwables that happen to compare
    // equal are still two real failures worth recording. Value equality here would drop one.
    @SuppressWarnings("ReferenceEquality")
    void done() {
        if (this.failures.isEmpty()) {
            return;
        }
        final Throwable first = this.failures.getFirst();
        this.failures.stream().skip(1).filter(t -> t != first).forEach(first::addSuppressed);
        throw sneakyThrow(first);
    }

    // Preserves the original throwable's type instead of wrapping it, so what the caller logs is
    // the real cause — including the checked exceptions Netty sneaky-throws out of
    // syncUninterruptibly(), which a wrapper would bury. Callers must catch Throwable to receive
    // it: an Error is a legitimate outcome here, and Daemon.stop() catches Throwable for exactly
    // that reason. Narrowing a caller back to Exception reintroduces the bug where one listener's
    // Error skips every listener after it.
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneakyThrow(final Throwable t) throws T {
        throw (T) t;
    }
}
