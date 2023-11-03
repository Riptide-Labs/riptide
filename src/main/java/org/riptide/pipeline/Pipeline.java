package org.riptide.pipeline;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.google.common.collect.Maps;
import org.riptide.classification.ClassificationEngine;
import org.riptide.classification.ClassificationRequest;
import org.riptide.classification.IpAddr;
import org.riptide.classification.Protocols;
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

    private final ClassificationEngine classificationEngine;

    // private final DocumentEnricherImpl documentEnricher;

    // private final InterfaceMarkerImpl interfaceMarker;

    // private final FlowThresholdingImpl thresholding;

    private final Map<String, Persister> persisters;

    private final EnrichedFlow.FlowMapper flowMapper;

    public Pipeline(final ClassificationEngine classificationEngine,
//                    final DocumentEnricherImpl documentEnricher,
//                    final InterfaceMarkerImpl interfaceMarker,
//                    final FlowThresholdingImpl thresholding,
                    final Map<String, FlowRepository> repositories,
                    final EnrichedFlow.FlowMapper flowMapper,
                    final MetricRegistry metricRegistry
    ) {
        this.classificationEngine = Objects.requireNonNull(classificationEngine);
//        this.documentEnricher = Objects.requireNonNull(documentEnricher);
//        this.interfaceMarker = Objects.requireNonNull(interfaceMarker);
//        this.thresholding = Objects.requireNonNull(thresholding);

        this.emptyFlows = metricRegistry.counter("emptyFlows");
        this.flowsPerLog = metricRegistry.histogram("flowsPerLog");

        this.logClassificationTimer = metricRegistry.timer("logClassification");
        this.logEnrichementTimer = metricRegistry.timer("logEnrichment");
        this.logMarkingTimer = metricRegistry.timer("logMarking");
        this.logThresholdingTimer = metricRegistry.timer("logThresholding");

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

        final List<EnrichedFlow> enrichedFlows = flows.stream()
                .map(flow -> this.flowMapper.enrichedFlow(source, flow))
                .collect(Collectors.toList());

        // Classify flows
        try (Timer.Context ctx  = this.logClassificationTimer.time()) {
            for (final var flow: enrichedFlows) {
                final var request = ClassificationRequest.builder()
                        .withExporterAddress(IpAddr.of(source.getExporterAddr()))
                        .withLocation(source.getLocation())
                        .withProtocol(Protocols.getProtocol(flow.getProtocol()))
                        .withSrcAddress(IpAddr.of(flow.getSrcAddr()))
                        .withSrcPort(flow.getSrcPort())
                        .withDstAddress(IpAddr.of(flow.getDstAddr()))
                        .withDstPort(flow.getDstPort())
                        .build();

                final var application = this.classificationEngine.classify(request);
                if (application != null) {
                    flow.setApplication(application);
                }
            }
        }

        // Enrich with model data
        LOG.debug("Enriching {} flow documents.", flows.size());
        try (Timer.Context ctx = this.logEnrichementTimer.time()) {
            //enrichedFlows = documentEnricher.enrich(flows, source);
            // TODO fooker: Can I haz real enrichment?
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
