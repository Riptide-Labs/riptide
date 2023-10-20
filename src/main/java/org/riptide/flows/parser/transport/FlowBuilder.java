package org.riptide.flows.parser.transport;

import org.riptide.flows.Flow;
import org.riptide.flows.parser.RecordEnrichment;
import org.riptide.flows.parser.ie.Value;

import java.time.Instant;
import java.util.Map;

public interface FlowBuilder {
    Flow buildFlow(final Instant receivedAt,
                   final Map<String, Value<?>> values,
                   final RecordEnrichment enrichment);
}
