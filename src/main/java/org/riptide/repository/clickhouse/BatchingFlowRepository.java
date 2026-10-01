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
import org.riptide.profiling.ProfilingLabels;
import org.riptide.repository.FlowRepository;
import org.riptide.telemetry.BusyTime;

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
 * queue, one or more background flushers ({@code riptide.clickhouse.batch.flushers}, default one)
 * drain them and hand the delegate one large insert per batch (at {@code maxRows} rows or after
 * {@code maxLatency}, whichever comes first). Each
 * ClickHouse insert forms a part and fires the four rollup materialized views, so collapsing the
 * per-record inserts into batches is what buys the throughput (see
 * {@link ClickhouseConfig.BatchConfig} for the sizing rationale).
 *
 * <p>Loss model: a full queue drops flows (counted, rate-limited warn) instead of blocking —
 * blocking would backpressure the parser executors into the Netty socket where loss is invisible.
 * Insert failures likewise surface as flusher error logs and the {@code failedRows} counter, not
 * as exceptions to the caller — and for a refused insert that counter charges the whole batch, so
 * there it is an upper bound on the loss rather than a tally of it (see {@code flush}). A refused
 * batch is additionally <em>dead-lettered</em> (#548): its rows are written to
 * {@code flows_dead_letter} so an operator can inspect and replay them deliberately, counted by
 * {@code deadLetteredRows}, and a dead-letter write that itself fails degrades to exactly the
 * behaviour above under {@code deadLetterFailedRows}. Riptide never replays a dead letter into
 * {@code flows} itself — see {@code FlowRepository#deadLetter} for why.
 * {@code stop()} rejects new flows and
 * drains everything already accepted within the shutdown grace period, preserving at-least-once
 * for accepted flows. A stopped instance cannot be restarted: {@code start()} after
 * {@code stop()} fails loud, because a half-alive instance that silently drops everything would
 * be worse than a crash.
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
    private static final long DROP_WARN_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(10);

    /**
     * How long stop() waits for each live flusher to unwind after the post-grace interrupt, before
     * sweeping the queue itself. Without it the sweep would drain the same queue concurrently
     * with a dying flusher, and delegate.stop() could run with an insert still in flight.
     */
    private static final long INTERRUPT_JOIN_MS = 1_000;

    private final FlowRepository delegate;

    private final ClickhouseConfig.BatchConfig config;

    private final LinkedBlockingQueue<EnrichedFlow> queue;

    /** Set once by stop(): producers reject-new, the flusher switches to its final drain. */
    private final AtomicBoolean stopped = new AtomicBoolean();

    /** The running flushers; empty before start() and after stop(). */
    private volatile List<Thread> flushers = List.of();

    /** N, from the config: every flusher adds its busy time divided by it. */
    private final int flusherCount;

    private final String flushersGauge;

    /** nanoTime, not wall clock: an NTP step backwards would mute drop warnings for the skew. */
    private final AtomicLong lastDropWarnNanos = new AtomicLong(System.nanoTime() - DROP_WARN_INTERVAL_NANOS);

    private final MetricRegistry metricRegistry;
    private final String queueDepthGauge;
    private final String flusherBusyName;
    private final BusyTime flusherBusy = new BusyTime();
    private final String queueCapacityGauge;

    /** The flusher's profiling labels, entered around every batch it inserts. */
    private final ProfilingLabels.Component profiling = ProfilingLabels.component("batch-writer", "flusher");
    private final Counter droppedRows;
    private final Counter failedRows;

    /**
     * Rows a refused insert would have dropped and that were kept in the dead-letter table instead
     * (#548). A subset of {@code failedRows}, never a replacement for it: those rows are still not
     * in {@code flows}.
     */
    private final Counter deadLetteredRows;

    /**
     * Rows that could not even be dead-lettered — the un-migrated deployment whose dead-letter table
     * does not exist, or a server that has gone away entirely. Counted apart from
     * {@link #deadLetteredRows} so the difference between "kept" and "gone" is visible on a
     * dashboard rather than only in the log.
     */
    private final Counter deadLetterFailedRows;

    private final Histogram batchSize;
    private final Timer flushTimer;

    public BatchingFlowRepository(final FlowRepository delegate,
                                  final ClickhouseConfig.BatchConfig config,
                                  final MetricRegistry metricRegistry) {
        this.delegate = Objects.requireNonNull(delegate);
        this.config = Objects.requireNonNull(config);
        this.metricRegistry = Objects.requireNonNull(metricRegistry);

        // Fail fast on nonsensical values: maxRows=0 would busy-spin the flusher, and
        // queueCapacity=0 would surface as an opaque LinkedBlockingQueue exception here.
        config.validate();

        this.flusherCount = config.getFlushers();

        this.queue = new LinkedBlockingQueue<>(config.getQueueCapacity());

        this.droppedRows = metricRegistry.counter(MetricRegistry.name("persister", "batch", "droppedRows"));
        this.failedRows = metricRegistry.counter(MetricRegistry.name("persister", "batch", "failedRows"));
        this.deadLetteredRows =
                metricRegistry.counter(MetricRegistry.name("persister", "batch", "deadLetteredRows"));
        this.deadLetterFailedRows =
                metricRegistry.counter(MetricRegistry.name("persister", "batch", "deadLetterFailedRows"));
        this.batchSize = metricRegistry.histogram(MetricRegistry.name("persister", "batch", "batchSize"));
        this.flushTimer = metricRegistry.timer(MetricRegistry.name("persister", "batch", "flush"));

        this.queueDepthGauge = MetricRegistry.name("persister", "batch", "queueDepth");
        this.queueCapacityGauge = MetricRegistry.name("persister", "batch", "queueCapacity");
        // Replace, don't keep: a stale gauge left by a previous instance would keep reading that
        // instance's dead queue — worse than no gauge at all. stop() unregisters it again.
        metricRegistry.remove(this.queueDepthGauge);
        metricRegistry.register(this.queueDepthGauge, (Gauge<Integer>) this.queue::size);
        // The flushers are this stage's ceiling, and the queue covers only seconds of load at
        // capacity, so busy time is the early warning and depth is not. Same lifecycle as queueDepth.
        this.flusherBusyName = MetricRegistry.name("persister", "batch", "flusherBusySeconds");
        metricRegistry.remove(this.flusherBusyName);
        metricRegistry.register(this.flusherBusyName, this.flusherBusy);
        // Beside the depth, so fill is a ratio of two scraped series rather than of a limit
        // hard-coded in a rule. Same lifecycle as queueDepth.
        final int capacity = config.getQueueCapacity();
        metricRegistry.remove(this.queueCapacityGauge);
        metricRegistry.register(this.queueCapacityGauge, (Gauge<Integer>) () -> capacity);
        // N beside the busy time, whose rate is the flushers' mean: "one at 80%" and "four at
        // 80%" read the same there. Same lifecycle as queueDepth.
        this.flushersGauge = MetricRegistry.name("persister", "batch", "flushers");
        metricRegistry.remove(this.flushersGauge);
        metricRegistry.register(this.flushersGauge, (Gauge<Integer>) () -> this.flusherCount);
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
        final long now = System.nanoTime();
        final long last = this.lastDropWarnNanos.get();
        // Rate-limited: under sustained overload every offer times out, and a warn per flow
        // would drown the log. The counter carries the exact tally.
        if (now - last >= DROP_WARN_INTERVAL_NANOS && this.lastDropWarnNanos.compareAndSet(last, now)) {
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
        if (!this.flushers.isEmpty()) {
            // A second start() would re-run the delegate's manage-mode DDL and orphan the
            // already-running flushers, which stop() then never joins.
            throw new IllegalStateException("BatchingFlowRepository is already started");
        }

        // Delegate first: no flusher may insert before the schema is ensured/validated.
        this.delegate.start();

        final List<Thread> threads = new ArrayList<>(this.flusherCount);
        for (int i = 0; i < this.flusherCount; i++) {
            // One flusher keeps today's name, so dumps, profiles and log greps still match.
            final String name = this.flusherCount == 1 ? "clickhouse-batch-flusher" : "clickhouse-batch-flusher-" + i;
            threads.add(new ThreadFactoryBuilder()
                    .setNameFormat(name)
                    .setDaemon(true)
                    .build()
                    .newThread(this::flushLoop));
        }
        this.flushers = List.copyOf(threads);
        threads.forEach(Thread::start);
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
                        // flushing. These rows already left the queue, so stop()'s leftover
                        // sweep cannot see them: count them here or they vanish from every
                        // counter.
                        //
                        // Not dead-lettered, deliberately (#548). The dead-letter write goes to the
                        // same server over the same client and on the same already-interrupted
                        // thread, so it would fail immediately; and these rows were never offered to
                        // ClickHouse, so nothing refused them — there is no refusal to preserve, only
                        // a shutdown that ran out of time.
                        Thread.currentThread().interrupt();
                        if (!batch.isEmpty()) {
                            this.failedRows.inc(batch.size());
                            log.warn("Flusher interrupted with {} rows drained but unflushed", batch.size());
                        }
                        return;
                    }
                }
                if (!batch.isEmpty()) {
                    // From the drain returning to the insert completing, inside the flusher's
                    // profiling scope; the drain's wait for rows is not work and is not counted (see BusyTime).
                    try (ProfilingLabels.Scope ignored = this.profiling.enter()) {
                        final long start = System.nanoTime();
                        try {
                            flush(batch);
                        } finally {
                            // Divided by N: the counter's rate stays the flushers' mean
                            // utilisation, 0 to 1, which the saturation alert and the dashboards
                            // read. With one flusher this is exactly addSince(start).
                            this.flusherBusy.add((System.nanoTime() - start) / this.flusherCount);
                        }
                    }
                }
            } catch (final Throwable e) {
                // Throwable on purpose: a silent death of a flusher (a metrics bug, an Error,
                // anything unforeseen) would still cut throughput — and with a single flusher
                // (the default) that cut is a permanent 100% drop.
                // Count whatever was in hand, log, and keep looping.
                // Charged in full. Whether that is exact depends on where the Throwable came from,
                // and this catch cannot tell: an Error out of delegate.persist escapes flush()'s
                // narrower catch with an insert genuinely in flight, so rows may be committed —
                // while an Error out of the drain or the histogram above never reached the server
                // at all. The message says "may" for that reason rather than claiming either.
                //
                // And not dead-lettered either (#548), for the same uncertainty one step further
                // out: this arm is reached by an Error or by anything unforeseen, so it cannot say
                // that `batch` is a complete batch a server refused — it may be a half-drained list,
                // or the failure may be the metrics registry rather than the insert. Calling the
                // delegate again from the arm that exists because the last call went wrong in an
                // unclassifiable way is what would turn one bad batch into a wedged flusher.
                this.failedRows.inc(batch.size());
                log.error("Unexpected error in the batch flusher, continuing. All {} rows are"
                        + " counted as failed; if the failure came from the insert, some may"
                        + " already be committed.", batch.size(), e);
            }
        }
    }

    /**
     * Hand one batch to the delegate. Never throws: a poison batch (mapping bug, rejected rows,
     * unreachable server after client-side retries) is logged and counted, and the flusher moves
     * on — one bad batch must not wedge the pipeline. An interrupt during the insert stays
     * visible on the thread (the delegate restores the flag), so the loop above still exits.
     *
     * <p>Charging the whole batch to {@code failedRows} makes the counter an upper bound on the
     * loss for this case, not an exact count of it: a server that refused the insert may still
     * have committed a prefix, since ClickHouse cuts an insert into blocks and can commit them
     * separately. Nothing here can tell the two apart, so the log admits the possibility rather
     * than promising the rows are gone.
     *
     * <p>"Flusher does not retry" in that message is about this loop only, and is not a claim
     * that no attempt was made: the client's own retries are already spent by the time an
     * exception reaches here. The message is pinned by
     * {@code BatchingFlowRepositoryTest#poisonBatchLogRefusesToClaimTheBatchWasDropped}.
     *
     * <p>The rows are then handed to {@link #deadLetterOrCount}, which keeps them for an operator (#548).
     * That is a second, independent statement and not a correction of the one above: the batch still
     * did not reach {@code flows}, {@code failedRows} still charges it in full, and whether the
     * server committed a prefix is exactly as unknown as before.
     */
    private void flush(final List<EnrichedFlow> batch) {
        this.batchSize.update(batch.size());
        try (var ctx = this.flushTimer.time()) {
            this.delegate.persist(batch);
        } catch (final FlowException | IOException | RuntimeException e) {
            this.failedRows.inc(batch.size());
            log.error("Failed to persist a batch of {} flows, flusher does not retry, some may be committed",
                    batch.size(), e);
            deadLetterOrCount(batch, e);
        }
    }

    /**
     * Keep the rows a refused insert would have dropped, and never throw doing it (#548).
     *
     * <p>{@code failedRows} is charged either way, by the caller, before this runs: it counts what
     * did not reach {@code flows}, and a dead-lettered row did not. {@link #deadLetteredRows} is the
     * second, separate statement — how many of those were kept — and
     * {@link #deadLetterFailedRows} is how many were not. An operator alerting on loss reads
     * {@code failedRows - deadLetteredRows}, and the difference is only visible because the two are
     * counted apart.
     *
     * <p><b>The fallback is exactly today's behaviour</b>: counted, logged once with the cause, and
     * the flusher carries on. That is what makes an un-migrated deployment — one whose dead-letter
     * table does not exist yet — degraded rather than broken.
     *
     * <p>{@code Throwable} on purpose, for the reason the flush loop catches one: this runs inside
     * the recovery path of a batch that has already failed, and a second failure here must not
     * escape into a loop whose only other option is to charge the same rows again. An interrupt is
     * re-flagged rather than swallowed, so a shutdown drain still converges.
     *
     * <p>Nothing here retries, re-inserts, or splits the batch — see
     * {@code FlowRepository#deadLetter} for why re-inserting into {@code flows} is the one thing
     * this design may never do.
     */
    private void deadLetterOrCount(final List<EnrichedFlow> batch, final Throwable cause) {
        try {
            this.delegate.deadLetter(batch, cause);
            this.deadLetteredRows.inc(batch.size());
            log.warn("Kept all {} flows of the refused batch in the dead-letter table for an operator"
                    + " to inspect; riptide never replays them into flows by itself", batch.size());
        } catch (final Throwable e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            this.deadLetterFailedRows.inc(batch.size());
            // Deliberately says only what it knows. The rows did not reach the dead-letter table;
            // whether the server kept part of the original batch is exactly as unknowable as it was
            // one line above, and this message must not resolve it either way.
            log.error("Could not keep the {} flows of a refused batch in the dead-letter table;"
                    + " they are counted as failed and nothing else was written", batch.size(), e);
        }
    }

    @Override
    public void stop() {
        if (!this.stopped.compareAndSet(false, true)) {
            // Idempotent: the drain and the delegate stop must run exactly once.
            return;
        }
        final List<Thread> threads = this.flushers;
        boolean graceExpired = false;
        if (!threads.isEmpty()) {
            // One deadline for all of them: the grace period is the service's shutdown budget,
            // and N flushers must not stretch it to N × grace. Each flusher sees the stop flag
            // after its current drain window (maxLatency < grace, enforced by validate()), then
            // drains the queue non-blocking and exits.
            final long deadline = System.nanoTime() + this.config.getShutdownGracePeriod().toNanos();
            for (final Thread thread : threads) {
                final long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                try {
                    thread.join(Math.max(1, remainingMillis));
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            final List<Thread> alive = threads.stream().filter(Thread::isAlive).toList();
            if (!alive.isEmpty()) {
                // Grace expired: a wedged or very slow insert. Interrupt every live flusher as a
                // last resort, then give each a moment to unwind: otherwise the sweep below drains
                // the queue concurrently with a dying thread and delegate.stop() can run with an
                // insert still in flight.
                graceExpired = true;
                alive.forEach(Thread::interrupt);
                log.warn("Batch flusher did not drain within {}; about {} accepted rows undelivered",
                        this.config.getShutdownGracePeriod(), this.queue.size());
                for (final Thread thread : alive) {
                    try {
                        thread.join(INTERRUPT_JOIN_MS);
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                if (alive.stream().anyMatch(Thread::isAlive)) {
                    // Best effort: proceed rather than hang shutdown on an unresponsive thread.
                    log.warn("Batch flusher still alive after the interrupt — continuing shutdown");
                }
            }
            this.flushers = List.of();
        }

        // Straggler sweep: a producer may pass the stopped check and offer after the flusher's
        // final drain — without this, those rows would be lost uncounted.
        sweep(graceExpired);

        this.delegate.stop();

        // A producer parked in the timed offer() can still land a row after the sweep. Nothing
        // can insert it any more (the delegate is stopped), but silent loss is the one outcome
        // this class must never have — count it.
        final List<EnrichedFlow> residue = new ArrayList<>();
        this.queue.drainTo(residue);
        if (!residue.isEmpty()) {
            this.droppedRows.inc(residue.size());
            log.warn("Dropping {} flows offered after the shutdown drain", residue.size());
        }

        // Unregister the gauges: left behind, they would describe this dead instance's queue forever.
        this.metricRegistry.remove(this.queueDepthGauge);
        this.metricRegistry.remove(this.flusherBusyName);
        this.metricRegistry.remove(this.queueCapacityGauge);
        this.metricRegistry.remove(this.flushersGauge);
    }

    /**
     * Drain whatever the flusher left behind, in {@code maxRows}-sized chunks: {@code
     * queueCapacity} is a multiple of {@code maxRows}, so one unchunked drain could produce an
     * insert several times larger than any the flusher would ever issue. The healthy path goes
     * through {@link #flush} so the batch-size histogram and flush timer see it too.
     *
     * @param graceExpired when the flusher had to be interrupted: the grace budget is spent and
     *                     the delegate is why, so another blocking insert would hang shutdown
     *                     past the service manager's stop timeout (the client has no socket
     *                     timeout by default) for rows unlikely to land anyway. Count and log.
     */
    private void sweep(final boolean graceExpired) {
        while (true) {
            final List<EnrichedFlow> chunk = new ArrayList<>();
            this.queue.drainTo(chunk, this.config.getMaxRows());
            if (chunk.isEmpty()) {
                return;
            }
            if (graceExpired) {
                // Not dead-lettered (#548): the grace budget is spent precisely because the delegate
                // is not answering, so a second blocking write to the same server is the hang this
                // branch exists to refuse. These rows were never offered either, so — as in the
                // interrupt arm above — there is no refusal to keep.
                this.failedRows.inc(chunk.size());
                log.error("Dropping {} leftover flows: the shutdown grace period is exhausted",
                        chunk.size());
            } else {
                flush(chunk);
            }
        }
    }
}
