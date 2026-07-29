/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser;

import com.google.common.util.concurrent.RateLimiter;
import com.codahale.metrics.Counter;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import lombok.extern.slf4j.Slf4j;
import org.riptide.flows.parser.data.Flow;
import org.riptide.flows.parser.session.SequenceNumberTracker;
import org.riptide.flows.parser.session.Session;
import org.riptide.pipeline.Identity;
import org.riptide.pipeline.Source;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.BiConsumer;

import static java.util.concurrent.TimeUnit.SECONDS;

@Slf4j
public abstract class ParserBase implements Parser {

    private static final int DEFAULT_NUM_THREADS = Runtime.getRuntime().availableProcessors() * 2;

    private final Protocol protocol;

    private final String name;

    private final BiConsumer<Source, List<Flow>> dispatcher;

    private final Identity identity;

    private final Meter recordsReceived;

    private final Meter recordsScheduled;

    private final Meter recordsDispatched;

    private final Counter sequenceErrors;

    private int threads = DEFAULT_NUM_THREADS;

    /**
     * Depth of the handoff queue between the listener and the workers. Four packets per worker:
     * deep enough to absorb the bursts UDP delivers without the listener stalling, shallow enough
     * that a genuinely overloaded pipeline reports it promptly instead of hiding a growing backlog.
     * Each entry is one packet's flows, so the resident bound is this times records-per-packet.
     */
    private int queueCapacity = DEFAULT_NUM_THREADS * 4;

    /** Packets dropped at the handoff because the workers were behind — the seam's loss ledger. */
    private final Counter dispatchDrops;

    /** One drop warning per 10s; the counter carries the tally. */
    private final RateLimiter dropWarnLimiter = RateLimiter.create(0.1);

    private int sequenceNumberPatience = 32;

    private ExecutorService executor;

    public ParserBase(final Protocol protocol,
                      final String name,
                      final BiConsumer<Source, List<Flow>> dispatcher,
                      final Identity identity,
                      final MetricRegistry metricRegistry) {
        this.protocol = Objects.requireNonNull(protocol);
        this.name = Objects.requireNonNull(name);
        this.dispatcher = Objects.requireNonNull(dispatcher);
        this.identity = Objects.requireNonNull(identity);

        this.recordsReceived = metricRegistry.meter(MetricRegistry.name("parsers", name, "recordsReceived"));
        this.recordsDispatched = metricRegistry.meter(MetricRegistry.name("parsers", name, "recordsDispatched"));
        this.recordsScheduled = metricRegistry.meter(MetricRegistry.name("parsers", name, "recordsScheduled"));
        this.sequenceErrors = metricRegistry.counter(MetricRegistry.name("parsers", name, "sequenceErrors"));
        this.dispatchDrops = metricRegistry.counter(MetricRegistry.name("parsers", name, "dispatchDrops"));

        setThreads(DEFAULT_NUM_THREADS);
    }

    @Override
    public void start(ScheduledExecutorService executorService) {
        // A bounded queue, and core == max so the workers exist up front.
        //
        // This used to be a zero-capacity SynchronousQueue whose rejection handler did a blocking
        // put(). Two consequences, both measured. A SynchronousQueue holds nothing, so "queue full"
        // is the steady state under load and the handler ran constantly; and put() blocks the
        // *submitting* thread, which here is the Netty event loop that owns the datagram channel
        // (CompletableFuture.runAsync calls execute() on the calling thread). So whenever every
        // worker was busy the listener stopped reading the socket, and the kernel receive buffer —
        // 256 MB of it — filled and dropped: at 4,400 exporters the collector plateaued at ~61k
        // rows/s with 1,245 receive-buffer errors/s while using only 2.4 of 4 cores. The loss was
        // invisible in riptide's own metrics because it happened in the kernel.
        //
        // A real queue lets the listener hand off and go straight back to draining the socket, and
        // when the queue does fill we drop here, counted, instead of pushing back into a place
        // where loss cannot be seen. Same convention as BatchingFlowRepository: bound it, drop on
        // full, count the drops.
        this.executor = new ThreadPoolExecutor(
                this.threads, this.threads,
                60L, SECONDS,
                new ArrayBlockingQueue<>(this.queueCapacity),
                (r, executor) -> {
                    // Never CallerRunsPolicy: the caller is the event loop, so running a task here
                    // stalls socket reads for every channel on that loop (and CERT TPS01-J warns it
                    // cannot prevent starvation deadlock when tasks block on I/O, which these do).
                    this.dispatchDrops.inc();
                    if (log.isWarnEnabled() && this.dropWarnLimiter.tryAcquire()) {
                        log.warn("Parser {} dispatch queue full ({} deep) — dropping a packet's flows; "
                                        + "{} drops so far. Enrichment or persistence cannot keep up.",
                                this.name, this.queueCapacity, this.dispatchDrops.getCount());
                    }
                });
    }

    @Override
    public void stop() {
        if (this.executor != null) {
            this.executor.shutdown();
            try {
                // Bounded wait for the in-flight dispatches to land in the repository (and its
                // batch buffer) before the pipeline behind them is stopped and drained.
                if (!this.executor.awaitTermination(5, SECONDS)) {
                    log.warn("Parser {} executor did not terminate in time; cancelling remaining dispatches", this.name);
                    // Cancel what is left: abandoned non-daemon workers would wedge JVM exit,
                    // and shutdownNow also unblocks a caller stuck in the rejection handler's
                    // blocking put().
                    this.executor.shutdownNow();
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while stopping parser {}; cancelling remaining dispatches", this.name);
                this.executor.shutdownNow();
            }
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

    public int getThreads() {
        return threads;
    }

    public int getQueueCapacity() {
        return this.queueCapacity;
    }

    public void setQueueCapacity(final int queueCapacity) {
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("Queue capacity must be >= 1");
        }
        this.queueCapacity = queueCapacity;
    }

    public void setThreads(int threads) {
        if (threads < 1) {
            throw new IllegalArgumentException("Threads must be >= 1");
        }
        this.threads = threads;
    }

    protected CompletableFuture<?> transmit(final Instant receivedAt,
                                            final FlowPacket packet,
                                            final Session session) {
        // one identity per packet: it scopes sequence tracking (below) and every
        // dispatched flow's Source
        final Source source = new Source(this.identity, packet.identity(session.getRemoteAddress()));

        // Verify that flows sequences are in order
        if (!session.verifySequenceNumber(source.identity(), packet.getSequenceNumber(), packet.getSequenceIncrement())) {
            log.warn("Error in flow sequence detected: from {}", source.identity());
            this.sequenceErrors.inc();
        }

        // One handoff per packet, not per record. Crossing this boundary is not free: it is a
        // park/unpark pair, and dispatching per record made the pipeline pay one per flow. Measured
        // at the ~61k rows/s ceiling the collector was doing ~150k parks/s — about 2.4 per record —
        // at only 2.4 of 4 cores, i.e. context-switch-bound rather than CPU-bound. A packet
        // carries ~24 records, so batching the handoff removes most of that traffic outright.
        //
        // It also stops defeating the enrichers: Enricher.Streaming/Single fan out across the flow
        // list with CompletableFuture.allOf, which did nothing while the list was always size 1.
        final List<Flow> flows = packet.buildFlows(receivedAt).toList();
        if (flows.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        this.recordsReceived.mark(flows.size());

        final Runnable dispatch = () -> {
            if (log.isTraceEnabled()) {
                flows.forEach(flow -> log.trace("Received flow: {}", flow));
            }

            this.dispatcher.accept(source, flows);

            this.recordsDispatched.mark(flows.size());
        };

        final var future = CompletableFuture.runAsync(dispatch, this.executor);

        this.recordsScheduled.mark(flows.size());

        return future.exceptionally(ex -> {
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
