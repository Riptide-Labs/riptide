package org.riptide.pipeline;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.google.common.collect.Maps;
import org.riptide.flows.parser.data.Flow;
import org.riptide.repository.FlowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

@Component
public class Pipeline {

    public static final String REPOSITORY_ID = "flows.repository.id";

    private static final Logger LOG = LoggerFactory.getLogger(Pipeline.class);

    private final Timer logClassificationTimer;

    private final Timer logEnrichementTimer;

    /**
     * Time taken to apply thresholding to a log
     */
    private final Timer logThresholdingTimer;

    /**
     * Time taken to mark the flows in a log
     */
    private final Timer logMarkingTimer;

    /**
     * Number of flows in a log
     */
    private final Histogram flowsPerLog;

    /**
     * Number of logs without a flow
     */
    private final Counter emptyFlows;

    private final List<Enricher> enrichers;
    private final Map<String, Persister> persisters;

    private final EnrichedFlow.FlowMapper flowMapper;

    public Pipeline(final List<Enricher> enrichers,
                    final Map<String, FlowRepository> repositories,
                    final MetricRegistry metricRegistry,
                    final EnrichedFlow.FlowMapper flowMapper
    ) {
        this.emptyFlows = metricRegistry.counter("emptyFlows");
        this.flowsPerLog = metricRegistry.histogram("flowsPerLog");

        this.logClassificationTimer = metricRegistry.timer("logClassification");
        this.logEnrichementTimer = metricRegistry.timer("logEnrichment");
        this.logMarkingTimer = metricRegistry.timer("logMarking");
        this.logThresholdingTimer = metricRegistry.timer("logThresholding");

        this.enrichers = Objects.requireNonNull(enrichers);

        this.persisters = Maps.transformEntries(repositories, (name, repository) -> {
            final var timer = metricRegistry.timer(MetricRegistry.name("logPersisting", name));
            return new Persister(repository, timer);
        });

        this.flowMapper = Objects.requireNonNull(flowMapper);
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
                enricher.enrich(source, enrichedFlows).join();
            }
        } catch (final CompletionException e) {
            throw new FlowException("Failed to enrich one or more flows.", e);
        }

        // Push flows to persistence
        for (final var persister : this.persisters.entrySet()) {
            try {
                persister.getValue().persist(enrichedFlows);
            } catch (final IOException e) {
                LOG.error("Failed to persist flows to {}", persister.getKey(), e);
            }
        }
    }

    private record Persister(FlowRepository repository, Timer logTimer) {
        private Persister(final FlowRepository repository, final Timer logTimer) {
            this.repository = Objects.requireNonNull(repository);
            this.logTimer = Objects.requireNonNull(logTimer);
        }

        public void persist(final Collection<EnrichedFlow> flows) throws FlowException, IOException {
            try (var ctx = this.logTimer.time()) {
                this.repository.persist(flows);
            }
        }
    }
}
