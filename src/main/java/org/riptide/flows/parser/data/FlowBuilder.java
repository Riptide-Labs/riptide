package org.riptide.flows.parser.data;

import org.riptide.flows.parser.RecordEnrichment;
import org.riptide.flows.parser.ie.Value;

import java.time.Instant;
import java.util.Map;

public interface FlowBuilder {
    Flow buildFlow(final Instant receivedAt,
                   final Map<String, Value<?>> values,
                   final RecordEnrichment enrichment);
}
