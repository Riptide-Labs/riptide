package org.riptide.flows.parser;

import java.util.concurrent.ScheduledExecutorService;

public interface Parser {
    String getName();
    String getDescription();

    Object dumpInternalState();

    void start(ScheduledExecutorService executorService);
    void stop();
}
