/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser;

import com.google.common.util.concurrent.RateLimiter;
import com.codahale.metrics.Counter;
import com.codahale.metrics.Gauge;
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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.BiConsumer;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

@Slf4j
public abstract class ParserBase implements Parser {

    /**
     * Enrichment is CPU-bound — six of the seven enabled enrichers complete synchronously — so
     * Goetz's formula collapses to roughly the core count and more threads buy context switches
     * rather than throughput. This was {@code availableProcessors() * 2}, which is about twice what
     * the work profile justifies; override per receiver with {@code threads} when the enricher mix
     * is I/O-heavy.
     */
    private static final int DEFAULT_NUM_THREADS = Runtime.getRuntime().availableProcessors();

    /** Guards against a configured capacity large enough to OOM on the pre-allocated array. */
    private static final int MAX_QUEUE_CAPACITY = 1 << 20;

    private final Protocol protocol;

    private final String name;

    private final BiConsumer<Source, List<Flow>> dispatcher;

    private final Identity identity;

    private final Meter recordsReceived;

    private final Meter recordsScheduled;

    private final Meter recordsDispatched;

    private final Counter sequenceErrors;

    private final MetricRegistry metricRegistry;

    /** volatile: written on the wiring thread, read by start() and by the event loops. */
    private volatile int threads = DEFAULT_NUM_THREADS;

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
     * <p>4096 packets is ~100k records at typical fan-out. <strong>Budget the memory as more than
     * the flow objects:</strong> each queued entry also pins the packet's retained {@code ByteBuf}
     * until its task runs, because {@link org.riptide.flows.listeners.UdpListener} releases the
     * buffer when the future completes. At the 8096-byte {@code FixedRecvByteBufAllocator} size that
     * is ~33 MB of pooled direct memory per parser on top of the heap cost, and a {@code multi}
     * receiver starts one parser per sub-protocol — so divide this down when configuring several.
     */
    private volatile int queueCapacity = 4096;

    /**
     * How long a UDP listener waits for queue space before dropping, letting the kernel's socket
     * buffer absorb a burst instead of discarding data it would have held — while never blocking
     * indefinitely the way the old {@code SynchronousQueue.put()} did. TCP does not use this: see
     * {@link #mayDropOnFullQueue()}.
     */
    private static final long OFFER_TIMEOUT_MS = 20;

    /** Records dropped at the handoff because the workers were behind — the seam's loss ledger. */
    private final Counter dispatchDrops;

    /** Data Sets discarded before decode because their Template was unknown. */
    private final Counter undecodableSets;

    /** One drop warning per 10s; the counter carries the tally. */
    private final RateLimiter dropWarnLimiter = RateLimiter.create(0.1);

    private volatile int sequenceNumberPatience = 32;

    /** Written by the shutdown thread, read by every event loop that submits work. */
    private volatile ThreadPoolExecutor executor;

    public ParserBase(final Protocol protocol,
                      final String name,
                      final BiConsumer<Source, List<Flow>> dispatcher,
                      final Identity identity,
                      final MetricRegistry metricRegistry) {
        this.protocol = Objects.requireNonNull(protocol);
        this.name = Objects.requireNonNull(name);
        this.dispatcher = Objects.requireNonNull(dispatcher);
        this.identity = Objects.requireNonNull(identity);
        this.metricRegistry = Objects.requireNonNull(metricRegistry);

        this.recordsReceived = metricRegistry.meter(MetricRegistry.name("parsers", name, "recordsReceived"));
        this.recordsDispatched = metricRegistry.meter(MetricRegistry.name("parsers", name, "recordsDispatched"));
        this.recordsScheduled = metricRegistry.meter(MetricRegistry.name("parsers", name, "recordsScheduled"));
        this.sequenceErrors = metricRegistry.counter(MetricRegistry.name("parsers", name, "sequenceErrors"));
        this.dispatchDrops = metricRegistry.counter(MetricRegistry.name("parsers", name, "dispatchDrops"));
        this.undecodableSets = metricRegistry.counter(MetricRegistry.name("parsers", name, "undecodableSets"));

        setThreads(DEFAULT_NUM_THREADS);
    }

    @Override
    public void start(ScheduledExecutorService executorService) {
        // A bounded queue with core == max, so the pool never grows or shrinks and the keep-alive
        // is inert. Replaces a zero-capacity SynchronousQueue whose rejection handler did an
        // unbounded blocking put(): with no capacity, "queue full" was the steady state under load,
        // and put() blocks the *submitting* thread — which for UDP is the single Netty event loop
        // that owns the datagram channel, since CompletableFuture.runAsync calls execute() on the
        // calling thread. A real queue lets that listener hand off and return to draining the socket.
        //
        // What this does NOT claim: that the handoff was the throughput ceiling. Appendix L of the
        // research doc retracted that — the rendezvous was 0.6% of listener samples, and the ceiling
        // was an O(exporters) scan since fixed in #389. The justification here is narrower and holds
        // on its own: fewer boundary crossings per record, and loss that is counted in userspace
        // rather than happening invisibly in the kernel receive buffer.
        final var pool = new ThreadPoolExecutor(
                this.threads, this.threads,
                60L, SECONDS,
                new ArrayBlockingQueue<>(this.queueCapacity));
        // Make the comment above true, and remove a real edge case: with zero live workers a task
        // handed straight to the queue would sit there unrun, and its future would never complete.
        pool.prestartAllCoreThreads();
        this.executor = pool;

        // Depth alongside drops, matching BatchingFlowRepository's queueDepth gauge: a rising depth
        // is the early warning, the drop counter is the damage report.
        final String depthGauge = MetricRegistry.name("parsers", this.name, "dispatchQueueDepth");
        this.metricRegistry.remove(depthGauge);
        this.metricRegistry.register(depthGauge,
                (Gauge<Integer>) () -> {
                    final var p = this.executor;
                    return p != null ? p.getQueue().size() : 0;
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
                    // Abandoned non-daemon workers would wedge JVM exit.
                    abandon(this.executor.shutdownNow());
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while stopping parser {}; cancelling remaining dispatches", this.name);
                abandon(this.executor.shutdownNow());
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
        if (queueCapacity < 1 || queueCapacity > MAX_QUEUE_CAPACITY) {
            throw new IllegalArgumentException(
                    "Queue capacity must be 1.." + MAX_QUEUE_CAPACITY + " (got " + queueCapacity + ")");
        }
        requireNotStarted();
        this.queueCapacity = queueCapacity;
    }

    public void setThreads(int threads) {
        if (threads < 1) {
            throw new IllegalArgumentException("Threads must be >= 1");
        }
        requireNotStarted();
        this.threads = threads;
    }

    /**
     * Both sizing knobs are read once, in {@link #start(ScheduledExecutorService)}. Silently
     * accepting a later change would leave the getter and the drop warning reporting a capacity the
     * queue does not have.
     */
    private void requireNotStarted() {
        if (this.executor != null) {
            throw new IllegalStateException(
                    "Parser " + this.name + " is already started; sizing is fixed at start()");
        }
    }

    /**
     * Whether a full dispatch queue may discard a packet.
     *
     * <p>Datagram transports may: the medium is already lossy, the sender gets no acknowledgement,
     * and a counted userspace drop is strictly better than pushing back into the kernel receive
     * buffer where the loss is invisible.
     *
     * <p>Reliable transports may not, which is why the default is {@code false}. On IPFIX/TCP the
     * exporter has already had its bytes acknowledged, so discarding them is silent data loss with
     * no retransmission — and {@code TcpListener} does not gate reads on the returned future, so
     * blocking the submitting thread is the only back-pressure available. It closes the receive
     * window and the exporter stops sending, which is the correct outcome. The cost is that the
     * worker loop is shared, so a saturated exporter can stall other connections on the same loop;
     * that is inherent to Netty's model and was the behaviour before the queue was introduced.
     */
    protected boolean mayDropOnFullQueue() {
        return false;
    }

    /**
     * Hand the task to the workers. Returns false only when the packet must be dropped, which can
     * only happen on a transport where {@link #mayDropOnFullQueue()} allows it.
     */
    private boolean enqueue(final DispatchTask task) {
        final var pool = this.executor;
        if (pool == null || pool.isShutdown()) {
            return false;
        }
        try {
            pool.execute(task);
            return true;
        } catch (final RejectedExecutionException e) {
            if (pool.isShutdown()) {
                return false;
            }
        }

        // Queue full. Submit through the pool rather than poking getQueue() directly: the latter is
        // documented as monitoring-only, and it bypasses the executor's own shutdown and
        // worker-liveness rechecks — a task inserted that way can be drained by a concurrent
        // shutdownNow() and leave its future pending, which leaks the packet's direct buffer.
        try {
            if (mayDropOnFullQueue()) {
                return pool.getQueue().offer(task, OFFER_TIMEOUT_MS, MILLISECONDS);
            }
            // Reliable transport: wait for space rather than lose acknowledged data.
            pool.getQueue().put(task);
            return true;
        } catch (final InterruptedException e) {
            // Deliberately NOT reinstating the interrupt flag. This runs on a Netty event-loop
            // thread, and Netty does not use interruption for shutdown — a set flag makes every
            // subsequent selector.select() return immediately, so the loop busy-spins a core
            // indefinitely. Failing the submission is enough: the caller completes the future, the
            // buffer is released, and the drop is counted.
            log.debug("Parser {} interrupted while enqueuing; dropping the packet", this.name);
            return false;
        }
    }

    /**
     * A dispatch task that can be accounted for after the fact. {@code shutdownNow()} hands back
     * whatever never ran, and this carries enough state for {@link #abandon(List)} to count those
     * records and — critically — complete their futures, so
     * {@link org.riptide.flows.listeners.UdpListener} releases the retained buffers instead of
     * leaking one per queued packet on every restart.
     */
    private final class DispatchTask implements Runnable {
        private final Source source;
        private final List<Flow> flows;
        private final CompletableFuture<Void> future = new CompletableFuture<>();

        private DispatchTask(final Source source, final List<Flow> flows) {
            this.source = source;
            this.flows = flows;
        }

        @Override
        public void run() {
            try {
                if (log.isTraceEnabled()) {
                    this.flows.forEach(flow -> log.trace("Received flow: {}", flow));
                }
                ParserBase.this.dispatcher.accept(this.source, this.flows);
                ParserBase.this.recordsDispatched.mark(this.flows.size());
                this.future.complete(null);
            } catch (final Throwable t) {
                this.future.completeExceptionally(t);
            }
        }
    }

    /** Count and release whatever {@code shutdownNow()} discarded, so no loss is silent. */
    private void abandon(final List<Runnable> discarded) {
        int records = 0;
        for (final Runnable r : discarded) {
            if (r instanceof DispatchTask task) {
                records += task.flows.size();
                task.future.complete(null);
            }
        }
        if (records > 0) {
            this.dispatchDrops.inc(records);
            log.warn("Parser {} discarded {} queued records ({} packets) that could not be drained "
                            + "within the shutdown grace period", this.name, records, discarded.size());
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
        // Counted before the isEmpty() return below, deliberately: a packet whose every Data Set was
        // undecodable builds no flows at all, so counting after the return would miss precisely the
        // loss this counter exists to make visible.
        final int undecodable = packet.undecodableSets();
        if (undecodable > 0) {
            this.undecodableSets.inc(undecodable);
        }

        final List<Flow> flows = packet.buildFlows(receivedAt).toList();
        if (flows.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        this.recordsReceived.mark(flows.size());

        // DispatchTask owns the future so that every exit path completes it: UdpListener releases
        // the packet's retained ByteBuf when this future completes, so any path that returns
        // something pending leaks a direct buffer (see #273). It also marks recordsDispatched only
        // after the dispatcher returns normally — the Daemon dispatcher swallows FlowException, so
        // marking unconditionally would count dropped flows as delivered and leave the one gauge an
        // operator uses to confirm delivery reading healthy while nothing reached ClickHouse.
        final var task = new DispatchTask(source, flows);

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

        final CompletableFuture<Void> future = task.future;
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
