/*
 * Copyright 2023 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser;

import java.util.concurrent.ScheduledExecutorService;

public interface Parser {
    String getName();
    String getDescription();

    Object dumpInternalState();

    void start(ScheduledExecutorService executorService);
    void stop();
}
