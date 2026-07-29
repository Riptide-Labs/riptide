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
import java.util.concurrent.RejectedExecutionException;
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
     * Depth of the handoff queue between the listener and the workers, in packets.
     *
     * <p>Sized from a measurement, not a guess. An earlier attempt used four packets per worker (32)
     * and throughput collapsed from ~61k to ~27k rows/s with 14 million records dropped here: UDP
     * arrives in bursts, so a shallow queue overflows during a burst while the workers idle between
     * them — they sat at under 2% CPU. The previous design accidentally had enormous burst
     * absorption, because blocking the listener pushed back into a 256 MB kernel socket buffer that
     * was doing the real queueing. Replacing that with 32 slots threw away data the kernel would
     * have held.
     *
     * <p>4096 packets is ~100k records at typical fan-out, roughly 20 MB resident — comparable
     * absorption, but bounded and counted in userspace where the loss is visible.
     */
    private int queueCapacity = 4096;

    /**
     * How long the listener will wait for queue space before dropping. Short and bounded: the point
     * is to let the kernel's socket buffer absorb a burst rather than discard it, while never
     * blocking the event loop indefinitely the way the old {@code SynchronousQueue.put()} did.
     */
    private static final long OFFER_TIMEOUT_MS = 20;

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
                new ArrayBlockingQueue<>(this.queueCapacity));
        // Default AbortPolicy on purpose, so rejection is thrown to the submitter rather than
        // swallowed here. The submitter owns the packet's retained ByteBuf, which UdpListener
        // releases when the returned future completes (see #273); a handler that quietly discarded
        // the task left that future pending forever and leaked one direct buffer per drop. That is
        // not theoretical — it exhausted the 2 GB direct-memory pool in about 90 seconds under
        // overload. Rejection is handled in transmit(), where the future and the counters are.
        //
        // Never CallerRunsPolicy either: the caller is the event loop, so running a task here would
        // stall socket reads for every channel on that loop, and CERT TPS01-J warns it cannot
        // prevent starvation deadlock when tasks block on I/O, which these do.
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

    /**
     * Hand the task to the workers, waiting briefly for space. Returns false only when the pipeline
     * is genuinely saturated, at which point the caller drops and counts.
     */
    private boolean enqueue(final Runnable task) {
        final var pool = (ThreadPoolExecutor) this.executor;
        try {
            pool.execute(task);
            return true;
        } catch (final RejectedExecutionException e) {
            if (pool.isShutdown()) {
                return false;
            }
        }
        try {
            return pool.getQueue().offer(task, OFFER_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
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

        // The future is built here rather than by runAsync so that every exit path completes it:
        // UdpListener releases the packet's retained ByteBuf when this future completes, so a path
        // that returns something pending leaks a direct buffer (see start(), and #273).
        final CompletableFuture<Void> future = new CompletableFuture<>();
        final Runnable task = () -> {
            try {
                dispatch.run();
                future.complete(null);
            } catch (final Throwable t) {
                future.completeExceptionally(t);
            }
        };

        if (!enqueue(task)) {
            this.dispatchDrops.inc(flows.size());
            if (log.isWarnEnabled() && this.dropWarnLimiter.tryAcquire()) {
                log.warn("Parser {} dispatch queue full ({} packets deep) even after waiting {} ms — "
                                + "dropped a packet's {} flows; {} records dropped so far. "
                                + "Enrichment or persistence cannot keep up.",
                        this.name, this.queueCapacity, OFFER_TIMEOUT_MS, flows.size(),
                        this.dispatchDrops.getCount());
            }
            return CompletableFuture.completedFuture(null);
        }

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
