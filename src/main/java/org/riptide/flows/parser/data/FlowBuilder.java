package org.riptide.flows.parser.data;

import org.riptide.flows.parser.ie.Value;

import java.time.Instant;
import java.util.Map;

public interface FlowBuilder {
    Flow buildFlow(Instant receivedAt, Map<String, Value<?>> values);
}
