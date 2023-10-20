package org.riptide.flows.pipeline;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.google.common.collect.Maps;
import org.riptide.flows.Flow;
import org.riptide.flows.repository.FlowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Component
public class Pipeline {

    public static final String REPOSITORY_ID = "flows.repository.id";

    private static final Logger LOG = LoggerFactory.getLogger(Pipeline.class);

    /**
     * Time taken to enrich the flows in a log
     */
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

    private final MetricRegistry metricRegistry;

    // private final DocumentEnricherImpl documentEnricher;

    // private final InterfaceMarkerImpl interfaceMarker;

    // private final FlowThresholdingImpl thresholding;

    private final Map<String, Persister> persisters;

    public Pipeline(final MetricRegistry metricRegistry,
//                    final DocumentEnricherImpl documentEnricher,
//                    final InterfaceMarkerImpl interfaceMarker,
//                    final FlowThresholdingImpl thresholding,
                    final Map<String, FlowRepository> repositories
    ) {
//        this.documentEnricher = Objects.requireNonNull(documentEnricher);
//        this.interfaceMarker = Objects.requireNonNull(interfaceMarker);
//        this.thresholding = Objects.requireNonNull(thresholding);

        this.emptyFlows = metricRegistry.counter("emptyFlows");
        this.flowsPerLog = metricRegistry.histogram("flowsPerLog");

        this.logEnrichementTimer = metricRegistry.timer("logEnrichment");
        this.logMarkingTimer = metricRegistry.timer("logMarking");
        this.logThresholdingTimer = metricRegistry.timer("logThresholding");

        this.metricRegistry = Objects.requireNonNull(metricRegistry);

        this.persisters = Maps.transformEntries(repositories, (pid, repository) -> {
            final var timer = this.metricRegistry.timer(MetricRegistry.name("logPersisting", pid));
            return new Persister(repository, timer);
        });
    }

    public void process(final WithSource<List<Flow>> flows) throws FlowException {
        // Track the number of flows per call
        this.flowsPerLog.update(flows.value().size());

        // Filter empty logs
        if (flows.value().isEmpty()) {
            this.emptyFlows.inc();
            LOG.info("Received empty flows from {} @ {}. Nothing to do.", flows.source(), flows.location());
            return;
        }

        // Enrich with model data
        LOG.debug("Enriching {} flow documents.", flows.value().size());
        final List<EnrichedFlow> enrichedFlows;
        try (final Timer.Context ctx = this.logEnrichementTimer.time()) {
            //enrichedFlows = documentEnricher.enrich(flows, source);
            // TODO fooker: Can I haz real enrichment?
            enrichedFlows = flows.value().stream()
                    .map(EnrichedFlow::from)
                    .collect(Collectors.toList());
        } catch (final Exception e) {
            throw new FlowException("Failed to enrich one or more flows.", e);
        }

        // Mark nodes and interfaces as having associated flows
//        try (final Timer.Context ctx = this.logMarkingTimer.time()) {
//            this.interfaceMarker.mark(enrichedFlows);
//        }

        // Apply thresholding to flows
//        try (final Timer.Context ctx = this.logThresholdingTimer.time()) {
//            this.thresholding.threshold(enrichedFlows, processingOptions);
//        } catch (final ThresholdInitializationException | ExecutionException e) {
//            throw new FlowException("Failed to threshold one or more flows.", e);
//        }

        // Push flows to persistence
        for (final var persister : this.persisters.entrySet()) {
            persister.getValue().persist(enrichedFlows);
        }
    }

//    @SuppressWarnings("rawtypes")
//    public synchronized void onBind(final FlowRepository repository, final Map properties) {
//        if (properties.get(REPOSITORY_ID) == null) {
//            LOG.error("Flow repository has no repository ID defined. Ignoring...");
//            return;
//        }
//
//        final String pid = Objects.toString(properties.get(REPOSITORY_ID));
//        this.persisters.put(pid, new Persister(repository,
//                this.metricRegistry.timer(MetricRegistry.name("logPersisting", pid))));
//    }
//
//    @SuppressWarnings("rawtypes")
//    public synchronized void onUnbind(final FlowRepository repository, final Map properties) {
//        if (properties.get(REPOSITORY_ID) == null) {
//            LOG.error("Flow repository has no repository ID defined. Ignoring...");
//            return;
//        }
//
//        final String pid = Objects.toString(properties.get(REPOSITORY_ID));
//        this.persisters.remove(pid);
//    }

    private static class Persister {
        public final FlowRepository repository;
        public final Timer logTimer;

        public Persister(final FlowRepository repository, final Timer logTimer) {
            this.repository = Objects.requireNonNull(repository);
            this.logTimer = Objects.requireNonNull(logTimer);
        }

        public void persist(final Collection<EnrichedFlow> flows) throws FlowException {
            try (final var ctx = this.logTimer.time()) {
                this.repository.persist(flows);
            }
        }
    }
}
