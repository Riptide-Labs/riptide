/*
 * Copyright 2023 Ronny Trommer <ronny@no42.org>
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
}
