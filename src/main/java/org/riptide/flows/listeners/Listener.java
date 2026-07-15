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
    String getDescription();
    void start();
    void stop();

    /**
     * Whether this receiver is currently bound and its socket active — i.e. its event loop is alive
     * and serving the channel. Used by the management health endpoints. Returns {@code false} before
     * {@link #start()} and after {@link #stop()}, or if the channel has died.
     */
    boolean isListening();
}
