/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.listeners;

import org.riptide.flows.parser.Parser;

/**
 * Interface used by the daemon to manage listeners.
 *
 * When messages are received, they should be forwarded to the given {@link Parser}s.
 *
 * @author jwhite
 */
public interface Listener {
    String getName();

    /**
     * Where this listener listens, for the startup log and diagnostics — for example
     * {@code UDP 0.0.0.0:9999}.
     *
     * <p>Must report the port actually bound once started, not the configured one: {@code port: 0}
     * asks the kernel to choose, and echoing back {@code 0} tells an operator nothing about where
     * the socket is. Must stop reporting it once the listener is no longer active, so a stopped
     * listener does not name a port another process may since have taken.
     *
     * <p>The host is reported as configured rather than read back from the channel — a wildcard
     * bind reads back as {@code 0:0:0:0:0:0:0:0} on a dual-stack JVM and a hostname as its resolved
     * literal, neither of which is what the operator wrote or can grep for. {@link ListenerAddress}
     * implements this split; implementations should use it rather than repeat the rules.
     */
    String getDescription();

    void start();

    /**
     * Releases everything {@link #start()} acquired.
     *
     * <p>Must be safe to call when {@code start()} never ran or did not finish: a listener is
     * constructed in one lifecycle phase and started in another, so a failure between them leaves a
     * constructed listener owning nothing. Implementations must not assume fields assigned during
     * {@code start()} are populated.
     *
     * <p>Release is best-effort across resources — a step that fails must not prevent the remaining
     * steps from being attempted, or a failed channel close would strand the event loop threads
     * behind it. Failures are reported once every step has been attempted, the first thrown with any
     * others attached via {@link Throwable#addSuppressed}; they are never silently swallowed.
     */
    void stop();

    /**
     * Whether this receiver is currently bound and its socket active — i.e. its event loop is alive
     * and serving the channel. Used by the management health endpoints. Returns {@code false} before
     * {@link #start()} and after {@link #stop()}, or if the channel has died.
     */
    boolean isListening();
}
