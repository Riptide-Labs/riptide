/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.repository.clickhouse;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.google.common.collect.Queues;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import lombok.extern.slf4j.Slf4j;
import org.riptide.config.ClickhouseConfig;
import org.riptide.pipeline.EnrichedFlow;
import org.riptide.pipeline.FlowException;
import org.riptide.repository.FlowRepository;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Batching decorator around a {@link FlowRepository}: producers enqueue flows into a bounded
 * queue, a single background flusher drains them and hands the delegate one large insert per
 * batch (at {@code maxRows} rows or after {@code maxLatency}, whichever comes first). Each
 * ClickHouse insert forms a part and fires the four rollup materialized views, so collapsing the
 * per-record inserts into batches is what buys the throughput (see
 * {@link ClickhouseConfig.BatchConfig} for the sizing rationale).
 *
 * <p>Loss model: a full queue drops flows (counted, rate-limited warn) instead of blocking —
 * blocking would backpressure the parser executors into the Netty socket where loss is invisible.
 * Insert failures likewise surface as flusher error logs and the {@code failedRows} counter, not
 * as exceptions to the caller. {@code stop()} rejects new flows and drains everything already
 * accepted within the shutdown grace period, preserving at-least-once for accepted flows. A
 * stopped instance cannot be restarted: {@code start()} after {@code stop()} fails loud, because
 * a half-alive instance that silently drops everything would be worse than a crash.
 */
@Slf4j
public class BatchingFlowRepository implements FlowRepository {

    /**
     * The offer budget for one persist() call — shared across its rows, not per row: under
     * sustained overload per-row waits would add up to exactly the blocking backpressure this
     * class exists to avoid. Short on purpose: a queue that stays full means ClickHouse cannot
     * keep up, and stalling the parser executors longer only moves the loss somewhere invisible.
     */
    private static final long OFFER_TIMEOUT_MS = 100;

    /** Minimum spacing between drop warnings; the dropped counter carries the exact tally. */
    private static final long DROP_WARN_INTERVAL_MS = 10_000;

    private final FlowRepository delegate;

    private final ClickhouseConfig.BatchConfig config;

    private final LinkedBlockingQueue<EnrichedFlow> queue;

    /** Set once by stop(): producers reject-new, the flusher switches to its final drain. */
    private final AtomicBoolean stopped = new AtomicBoolean();

    private volatile Thread flusher;

    private final AtomicLong lastDropWarnMillis = new AtomicLong();

    private final Counter droppedRows;
    private final Counter failedRows;
    private final Histogram batchSize;
    private final Timer flushTimer;

    public BatchingFlowRepository(final FlowRepository delegate,
                                  final ClickhouseConfig.BatchConfig config,
                                  final MetricRegistry metricRegistry) {
        this.delegate = Objects.requireNonNull(delegate);
        this.config = Objects.requireNonNull(config);
        Objects.requireNonNull(metricRegistry);

        // Fail fast on nonsensical values: maxRows=0 would busy-spin the flusher, and
        // queueCapacity=0 would surface as an opaque LinkedBlockingQueue exception here.
        config.validate();

        this.queue = new LinkedBlockingQueue<>(config.getQueueCapacity());

        this.droppedRows = metricRegistry.counter(MetricRegistry.name("persister", "batch", "droppedRows"));
        this.failedRows = metricRegistry.counter(MetricRegistry.name("persister", "batch", "failedRows"));
        this.batchSize = metricRegistry.histogram(MetricRegistry.name("persister", "batch", "batchSize"));
        this.flushTimer = metricRegistry.timer(MetricRegistry.name("persister", "batch", "flush"));

        final String queueDepthGauge = MetricRegistry.name("persister", "batch", "queueDepth");
        // Replace, don't keep: a stale gauge left by a previous instance would keep reading that
        // instance's dead queue — worse than no gauge at all.
        metricRegistry.remove(queueDepthGauge);
        metricRegistry.register(queueDepthGauge, (Gauge<Integer>) this.queue::size);
    }

    @Override
    public void persist(final List<EnrichedFlow> flows) throws FlowException, IOException {
        // One offer budget for the whole call (see OFFER_TIMEOUT_MS): the first rows may wait
        // for space, and once the budget is spent the rest gets non-blocking offers only.
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(OFFER_TIMEOUT_MS);
        for (int i = 0; i < flows.size(); i++) {
            if (this.stopped.get()) {
                // Reject-new after stop(): the drain must converge on the rows accepted so far.
                drop(flows.size() - i, "repository is stopping");
                return;
            }
            try {
                final long remaining = deadline - System.nanoTime();
                final boolean accepted = remaining > 0
                        ? this.queue.offer(flows.get(i), remaining, TimeUnit.NANOSECONDS)
                        : this.queue.offer(flows.get(i));
                if (!accepted) {
                    // Budget exhausted against a still-full queue: drop the remainder in one go
                    // rather than burning a timeout per row.
                    drop(flows.size() - i, "queue is full — ClickHouse cannot keep up");
                    return;
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                drop(flows.size() - i, "producer interrupted");
                return;
            }
        }
    }

    private void drop(final int rows, final String reason) {
        this.droppedRows.inc(rows);
        final long now = System.currentTimeMillis();
        final long last = this.lastDropWarnMillis.get();
        // Rate-limited: under sustained overload every offer times out, and a warn per flow
        // would drown the log. The counter carries the exact tally.
        if (now - last >= DROP_WARN_INTERVAL_MS && this.lastDropWarnMillis.compareAndSet(last, now)) {
            log.warn("Dropping flows ({}); {} dropped in total", reason, this.droppedRows.getCount());
        }
    }

    @Override
    public void start() {
        if (this.stopped.get()) {
            // Fail loud: a "restarted" instance would accept nothing and silently drop every
            // flow — the worst possible failure mode for a persister.
            throw new IllegalStateException("BatchingFlowRepository is stopped and cannot be restarted");
        }

        // Delegate first: the flusher must not insert before the schema is ensured/validated.
        this.delegate.start();

        final Thread thread = new ThreadFactoryBuilder()
                .setNameFormat("clickhouse-batch-flusher")
                .setDaemon(true)
                .build()
                .newThread(this::flushLoop);
        this.flusher = thread;
        thread.start();
    }

    private void flushLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            // A fresh list per batch: the delegate (and tests) may retain the reference.
            final List<EnrichedFlow> batch = new ArrayList<>();
            try {
                if (this.stopped.get()) {
                    // Final drain: non-blocking, so the loop exits as soon as the queue is empty.
                    this.queue.drainTo(batch, this.config.getMaxRows());
                    if (batch.isEmpty()) {
                        return;
                    }
                } else {
                    try {
                        // Blocks until maxRows are available or maxLatency elapsed — the two
                        // flush triggers in one call.
                        Queues.drain(this.queue, batch, this.config.getMaxRows(), this.config.getMaxLatency());
                    } catch (final InterruptedException e) {
                        // Only stop() interrupts us, and only after the grace period expired —
                        // the insert below would be interrupted too, so give up instead of
                        // flushing; stop()'s leftover sweep accounts for what stays behind.
                        Thread.currentThread().interrupt();
                        if (!batch.isEmpty()) {
                            log.warn("Flusher interrupted with {} rows drained but unflushed", batch.size());
                        }
                        return;
                    }
                }
                if (!batch.isEmpty()) {
                    flush(batch);
                }
            } catch (final Throwable e) {
                // Throwable on purpose: this is the only flusher, and a silent death (a metrics
                // bug, an Error, anything unforeseen) would turn into a permanent 100% drop.
                // Count whatever was in hand, log, and keep looping.
                this.failedRows.inc(batch.size());
                log.error("Unexpected error in the batch flusher — continuing", e);
            }
        }
    }

    /**
     * Hand one batch to the delegate. Never throws: a poison batch (mapping bug, rejected rows,
     * unreachable server after client-side retries) is logged and counted, and the flusher moves
     * on — one bad batch must not wedge the pipeline. An interrupt during the insert stays
     * visible on the thread (the delegate restores the flag), so the loop above still exits.
     */
    private void flush(final List<EnrichedFlow> batch) {
        this.batchSize.update(batch.size());
        try (var ctx = this.flushTimer.time()) {
            this.delegate.persist(batch);
        } catch (final FlowException | IOException | RuntimeException e) {
            this.failedRows.inc(batch.size());
            log.error("Failed to persist a batch of {} flows — dropping the batch", batch.size(), e);
        }
    }

    @Override
    public void stop() {
        if (!this.stopped.compareAndSet(false, true)) {
            // Idempotent: the drain and the delegate stop must run exactly once.
            return;
        }
        final Thread thread = this.flusher;
        boolean graceExpired = false;
        if (thread != null) {
            try {
                // The flusher sees the stop flag at the latest after the current drain window
                // (maxLatency < grace, enforced by validate()), then drains the queue
                // non-blocking and exits.
                thread.join(Math.max(1, this.config.getShutdownGracePeriod().toMillis()));
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (thread.isAlive()) {
                // Grace expired — a wedged or very slow insert. Interrupt as a last resort and
                // account for what stays behind.
                graceExpired = true;
                thread.interrupt();
                log.warn("Batch flusher did not drain within {}; about {} accepted rows undelivered",
                        this.config.getShutdownGracePeriod(), this.queue.size());
            }
            this.flusher = null;
        }
        // Straggler sweep: a producer may pass the stopped check and offer after the flusher's
        // final drain — without this, those rows would be lost uncounted. One best-effort insert.
        final List<EnrichedFlow> leftovers = new ArrayList<>();
        this.queue.drainTo(leftovers);
        if (!leftovers.isEmpty()) {
            if (graceExpired) {
                // The grace budget is spent and the delegate is why. Another blocking insert
                // would hang shutdown past the service manager's stop timeout (the client has
                // no socket timeout by default) for rows that are unlikely to land anyway.
                this.failedRows.inc(leftovers.size());
                log.error("Dropping {} leftover flows: the shutdown grace period is exhausted",
                        leftovers.size());
            } else {
                try {
                    this.delegate.persist(leftovers);
                } catch (final FlowException | IOException | RuntimeException e) {
                    this.failedRows.inc(leftovers.size());
                    log.error("Failed to persist {} leftover flows during shutdown — dropping them",
                            leftovers.size(), e);
                }
            }
        }
        this.delegate.stop();
    }
}
