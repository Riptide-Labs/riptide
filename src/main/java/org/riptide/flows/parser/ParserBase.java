package org.riptide.flows.parser;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import lombok.extern.slf4j.Slf4j;
import org.riptide.flows.parser.data.Flow;
import org.riptide.flows.parser.session.SequenceNumberTracker;
import org.riptide.flows.parser.session.Session;
import org.riptide.pipeline.Source;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BiConsumer;

@Slf4j
public abstract class ParserBase implements Parser {

    private final Protocol protocol;

    private final String name;

    private final BiConsumer<Source, Flow> dispatcher;

    private final String location;

    private final Meter recordsReceived;

    private final Meter recordsScheduled;

    private final Meter recordsDispatched;

    private final Counter sequenceErrors;

    private int sequenceNumberPatience = 32;

    private ExecutorService executor;

    public ParserBase(final Protocol protocol,
                      final String name,
                      final BiConsumer<Source, Flow> dispatcher,
                      final String location,
                      final MetricRegistry metricRegistry) {
        this.protocol = Objects.requireNonNull(protocol);
        this.name = Objects.requireNonNull(name);
        this.dispatcher = Objects.requireNonNull(dispatcher);
        this.location = Objects.requireNonNull(location);

        this.recordsReceived = metricRegistry.meter(MetricRegistry.name("parsers", name, "recordsReceived"));
        this.recordsDispatched = metricRegistry.meter(MetricRegistry.name("parsers", name, "recordsDispatched"));
        this.recordsScheduled = metricRegistry.meter(MetricRegistry.name("parsers", name, "recordsScheduled"));
        this.sequenceErrors = metricRegistry.counter(MetricRegistry.name("parsers", name, "sequenceErrors"));
    }

    @Override
    public void start(ScheduledExecutorService executorService) {
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override
    public void stop() {
        if (this.executor != null) {
            this.executor.shutdown();
            this.executor = null;
        }
    }

    @Override
    public String getName() {
        return this.name;
    }

    @Override
    public String getDescription() {
        return this.protocol.description;
    }

    public int getSequenceNumberPatience() {
        return this.sequenceNumberPatience;
    }

    public void setSequenceNumberPatience(final int sequenceNumberPatience) {
        this.sequenceNumberPatience = sequenceNumberPatience;
    }

    protected CompletableFuture<?> transmit(final Instant receivedAt,
                                            final FlowPacket packet,
                                            final Session session,
                                            final InetSocketAddress remoteAddress) {
        // Verify that flows sequences are in order
        if (!session.verifySequenceNumber(packet.getObservationDomainId(), packet.getSequenceNumber())) {
            log.warn("Error in flow sequence detected: from {}", session.getRemoteAddress());
            this.sequenceErrors.inc();
        }

        final var futures = packet.buildFlows(receivedAt).map(flow -> {
            this.recordsReceived.mark();

            final Runnable dispatch = () -> {
                log.trace("Received flow: {}", flow);

                this.dispatcher.accept(new Source(this.location, session.getRemoteAddress()), flow);

                this.recordsDispatched.mark();
            };

            final var future = CompletableFuture.runAsync(dispatch, executor);

            this.recordsScheduled.mark();

            return future;
        });

        return CompletableFuture.allOf(futures.toArray(CompletableFuture<?>[]::new))
                .exceptionally(ex -> {
                    if (ex != null) {
                        log.warn("Error preparing records for dispatch.", ex);
                    }

                    return null;
                });
    }

    protected SequenceNumberTracker sequenceNumberTracker() {
        return new SequenceNumberTracker(this.sequenceNumberPatience);
    }
}
