package org.riptide.pipeline;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import org.riptide.flows.parser.data.Flow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Component
public class Pipeline {

    private static final Logger LOG = LoggerFactory.getLogger(Pipeline.class);

    private final Timer logEnrichementTimer;

    /**
     * Number of flows in a log
     */
    private final Histogram flowsPerLog;

    /**
     * Number of logs without a flow
     */
    private final Counter emptyFlows;

    private final List<Enricher> enrichers;
    private final List<FlowPersister> persisters;

    private final EnrichedFlow.FlowMapper flowMapper;

    public Pipeline(final List<Enricher> enrichers,
                    final List<FlowPersister> persisters,
                    final MetricRegistry metricRegistry,
                    final EnrichedFlow.FlowMapper flowMapper
    ) {
        this.flowMapper = Objects.requireNonNull(flowMapper);
        this.emptyFlows = metricRegistry.counter("emptyFlows");
        this.flowsPerLog = metricRegistry.histogram("flowsPerLog");
        this.logEnrichementTimer = metricRegistry.timer("logEnrichment");
        this.enrichers = Objects.requireNonNull(enrichers);
        this.persisters = Objects.requireNonNull(persisters);
    }

    public void process(final Source source, final List<Flow> flows) throws FlowException {
        // Track the number of flows per call
        this.flowsPerLog.update(flows.size());

        // Filter empty logs
        if (flows.isEmpty()) {
            this.emptyFlows.inc();
            LOG.info("Received empty flows from {} @ {}. Nothing to do.", source.getExporterAddr(), source.getLocation());
            return;
        }

        // Enrich with model data
        LOG.debug("Enriching {} flow documents.", flows.size());
        final List<EnrichedFlow> enrichedFlows;
        try (Timer.Context ctx = this.logEnrichementTimer.time()) {
            enrichedFlows = flows.stream()
                    .map(flow -> this.flowMapper.enrichedFlow(source, flow))
                    .collect(Collectors.toList());

            for (final var enricher : this.enrichers) {
                enricher.enrich(source, enrichedFlows).get();
            }
        } catch (final Exception e) {
            throw new FlowException("Failed to enrich one or more flows.", e);
        }

        // Push flows to persistence
        for (final var persister : this.persisters) {
            try {
                persister.persist(enrichedFlows);
            } catch (final IOException e) {
                LOG.error("Failed to persist flows to {}", persister.getName(), e);
            }
        }
    }
}
