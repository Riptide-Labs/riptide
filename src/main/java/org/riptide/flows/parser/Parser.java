/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser;


public interface Parser {
    String getName();
    String getDescription();

    Object dumpInternalState();

    void start();
    void stop();
}
