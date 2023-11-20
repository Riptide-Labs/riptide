package org.riptide.flows.parser;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import lombok.extern.slf4j.Slf4j;
import org.riptide.flows.parser.data.Flow;
import org.riptide.flows.parser.data.FlowBuilder;
import org.riptide.flows.parser.ie.RecordProvider;
import org.riptide.flows.parser.session.SequenceNumberTracker;
import org.riptide.flows.parser.session.Session;
import org.riptide.pipeline.Source;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.BiConsumer;

import static java.util.concurrent.TimeUnit.SECONDS;

@Slf4j
public abstract class ParserBase implements Parser {

    private static final int DEFAULT_NUM_THREADS = Runtime.getRuntime().availableProcessors() * 2;

    private final Protocol protocol;

    private final String name;

    private final BiConsumer<Source, Flow> dispatcher;

    private final String location;

    private final Meter recordsReceived;

    private final Meter recordsScheduled;

    private final Meter recordsDispatched;

    private final Counter sequenceErrors;

    private int threads = DEFAULT_NUM_THREADS;

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

        setThreads(DEFAULT_NUM_THREADS);
    }

    protected abstract FlowBuilder getFlowBulder();

    @Override
    public void start(ScheduledExecutorService executorService) {
        this.executor = new ThreadPoolExecutor(
                // corePoolSize must be > 0 since we use the RejectedExecutionHandler to block when the queue is full
                1, this.threads,
                60L, SECONDS,
                new SynchronousQueue<>(),
                (r, executor) -> {
                    // We enter this block when the queue is full and the caller is attempting to submit additional tasks
                    try {
                        // If we're not shutdown, then block until there's room in the queue
                        if (!executor.isShutdown()) {
                            executor.getQueue().put(r);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RejectedExecutionException("Executor interrupted while waiting for capacity in the work queue.", e);
                    }
                });
    }

    @Override
    public void stop() {
        executor.shutdown();
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

    public int getThreads() {
        return threads;
    }

    public void setThreads(int threads) {
        if (threads < 1) {
            throw new IllegalArgumentException("Threads must be >= 1");
        }
        this.threads = threads;
    }

    protected CompletableFuture<?> transmit(final Instant receivedAt,
                                            final RecordProvider packet,
                                            final Session session,
                                            final InetSocketAddress remoteAddress) {
        // Verify that flows sequences are in order
        if (!session.verifySequenceNumber(packet.getObservationDomainId(), packet.getSequenceNumber())) {
            log.warn("Error in flow sequence detected: from {}", session.getRemoteAddress());
            this.sequenceErrors.inc();
        }

        final var futures = packet.getRecords().map(record -> {
            this.recordsReceived.mark();

            // We're currently in the callback thread from the enrichment process
            // We want the remainder of the serialization and dispatching to be performed
            // from one of our executor threads so that we can put back-pressure on the listener
            // if we can't keep up
            final Runnable dispatch = () -> {
                // Let's serialize
                final Flow flow;
                try {
                    flow = this.getFlowBulder().buildFlow(receivedAt, record);
                } catch (final Exception e) {
                    throw new RuntimeException(e);
                }

                log.trace("Received flow: {}", flow);

                // Dispatch
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
