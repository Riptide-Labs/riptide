/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.repository.clickhouse;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.codahale.metrics.MetricRegistry;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.riptide.config.ClickhouseConfig;
import org.riptide.pipeline.EnrichedFlow;
import org.riptide.pipeline.FlowException;
import org.riptide.repository.FlowRepository;
import org.riptide.repository.TestRepository;
import org.riptide.testsupport.LogCapture;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;

/**
 * Unit tests for the batching decorator against an in-memory delegate: the flush triggers, the
 * drop policy, poison-batch resilience, the shutdown drain, the lifecycle guards, and the config
 * validation.
 */
class BatchingFlowRepositoryTest {

    private final ObservableRepository delegate = new ObservableRepository();

    private final MetricRegistry metricRegistry = new MetricRegistry();

    private BatchingFlowRepository repository;

    private Logger repositoryLog;

    private ListAppender<ILoggingEvent> logEvents;

    private Level originalLogLevel;

    @BeforeEach
    void captureFlusherLog() {
        this.repositoryLog = (Logger) LoggerFactory.getLogger(BatchingFlowRepository.class);
        this.originalLogLevel = this.repositoryLog.getLevel();
        // TRACE, not ERROR: the negative assertion below hunts a loss claim anywhere in this
        // class's output, and pinning at ERROR would filter out the very events it looks for — a
        // WARN restating "dropping the batch" beside the correct ERROR line would go unseen. The
        // positive branch asserts the level explicitly, so widening the capture cannot make the
        // test vacuous. Pinned rather than inherited so ambient test logging cannot narrow it.
        this.repositoryLog.setLevel(Level.TRACE);
        this.logEvents = LogCapture.startedAppender();
        this.repositoryLog.addAppender(this.logEvents);
    }

    /**
     * One method, not two: the logger restore has to happen after the repository is stopped, and
     * JUnit does not order two {@code @AfterEach} methods of the same class relative to each
     * other. Every field is null-guarded so a {@code @BeforeEach} that threw before assigning
     * them reports its own cause instead of an NPE from the teardown that followed it.
     */
    @AfterEach
    void tearDown() {
        if (this.repository != null) {
            this.delegate.unblock();
            this.repository.stop();
        }
        if (this.repositoryLog != null) {
            if (this.logEvents != null) {
                this.repositoryLog.detachAppender(this.logEvents);
                this.logEvents.stop();
            }
            this.repositoryLog.setLevel(this.originalLogLevel);
        }
    }

    @Test
    void flushesWhenBatchSizeIsReachedBeforeMaxLatency() throws Exception {
        // The await deadline is shorter than maxLatency: only the size trigger can pass it.
        this.repository = repository(batchConfig(5, Duration.ofMillis(1500)));
        this.repository.start();

        this.repository.persist(flows(5));

        await(Duration.ofMillis(750), "size-triggered flush", () -> this.delegate.count() == 5);
        Assertions.assertThat(this.delegate.inserts.get()).isEqualTo(1);
    }

    @Test
    void flushesBufferedRowsWhenMaxLatencyElapses() throws Exception {
        // maxRows far above what is offered: only the time trigger can flush.
        this.repository = repository(batchConfig(10_000, Duration.ofMillis(150)));
        this.repository.start();

        for (final var flow : flows(3)) {
            this.repository.persist(List.of(flow));
        }

        await(Duration.ofSeconds(3), "time-triggered flush", () -> this.delegate.count() == 3);
    }

    @Test
    void emptyFlushWindowCausesNoInsert() throws Exception {
        this.repository = repository(batchConfig(10, Duration.ofMillis(50)));
        this.repository.start();

        // Nothing is offered, so several flush windows pass empty — assert throughout that the
        // delegate never sees an insert (an empty insert would show as inserts > 0, not count).
        final var deadline = Instant.now().plusMillis(300);
        while (Instant.now().isBefore(deadline)) {
            Assertions.assertThat(this.delegate.inserts.get()).isZero();
            Thread.sleep(20);
        }

        this.repository.stop();
        Assertions.assertThat(this.delegate.inserts.get()).isZero();
    }

    @Test
    void dropsFlowsWhenTheQueueStaysFull() throws Exception {
        // Wedge the delegate so the single-row batches stop draining: one row in flight, one row
        // filling the capacity-1 queue, and the third offer must time out and drop.
        this.delegate.block();
        final var config = batchConfig(1, Duration.ofMillis(300));
        config.setQueueCapacity(1);
        this.repository = repository(config);
        this.repository.start();

        final var flows = flows(3);
        this.repository.persist(List.of(flows.get(0)));
        await(Duration.ofSeconds(3), "first row in flight", () -> this.delegate.inserts.get() == 1);
        this.repository.persist(List.of(flows.get(1)));
        this.repository.persist(List.of(flows.get(2)));

        Assertions.assertThat(droppedRows()).isEqualTo(1);

        // Un-wedge: the two accepted rows must still be delivered by the shutdown drain.
        this.delegate.unblock();
        this.repository.stop();
        Assertions.assertThat(this.delegate.count()).isEqualTo(2);
    }

    @Test
    void multiRowPersistDropsTheRemainderWhenTheQueueFillsUp() throws Exception {
        // Same wedge as above, but with one multi-row persist call: its offer budget is per
        // call, so once the capacity-1 queue is full the whole remainder drops in one go.
        this.delegate.block();
        final var config = batchConfig(1, Duration.ofMillis(300));
        config.setQueueCapacity(1);
        this.repository = repository(config);
        this.repository.start();

        final var flows = flows(4);
        this.repository.persist(List.of(flows.get(0)));
        await(Duration.ofSeconds(3), "first row in flight", () -> this.delegate.inserts.get() == 1);
        // Three rows in one call: the first fills the queue, the remaining two are the remainder.
        this.repository.persist(flows.subList(1, 4));

        Assertions.assertThat(droppedRows()).isEqualTo(2);

        this.delegate.unblock();
        this.repository.stop();
        Assertions.assertThat(this.delegate.count()).isEqualTo(2);
    }

    @Test
    void poisonBatchIsCountedAndSubsequentBatchesStillFlush() throws Exception {
        attemptOnePoisonedBatchOfTwo();

        this.repository.persist(flows(2));

        await(Duration.ofSeconds(3), "flush after the poison batch", () -> this.delegate.count() == 2);
        Assertions.assertThat(failedRows()).isEqualTo(2);
        // clickhouse.md states as fact that the drop counter stays at zero for an insert failure,
        // so that the two counters keep meaning different things to an operator alerting on them.
        Assertions.assertThat(droppedRows())
                .as("a refused insert is a failure, not a drop")
                .isZero();
    }

    /**
     * The flush loop's {@code catch (Throwable)} is the sibling of {@code flush()}'s catch: it
     * charges the same counter in full, from a point where an insert may already have been in
     * flight. Its message carried none of the partial-commit caveat until #713, and the sibling one
     * level down has been pinned since #709 — so without this row the caveat could be dropped from
     * exactly one of the two with the suite still green, which is the regression #713 reports.
     */
    @Test
    void theFlusherErrorLogAdmitsTheBatchMayBePartlyCommitted() throws Exception {
        this.delegate.errorsRemaining.set(1);
        this.repository = repository(batchConfig(2, Duration.ofMillis(600)));

        this.repository.persist(flows(2));
        this.repository.start();
        await(Duration.ofSeconds(3), "the flusher survived the Error", () -> failedRows() == 2);
        this.repository.stop();

        Assertions.assertThat(this.logEvents.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .as("the flush loop's own error line must say the count is not exact")
                .anySatisfy(message -> Assertions.assertThat(message)
                        .contains("Unexpected error in the batch flusher")
                        .contains("All 2 rows are counted as failed")
                        .contains("may")
                        .contains("committed"));
    }

    @Test
    void poisonBatchLogRefusesToClaimTheBatchWasDropped() throws Exception {
        // The flusher's ERROR line is the operator's first signal, and two docs pages state as
        // fact that it admits the batch may be partly committed — a refused ClickHouse insert is
        // not always atomic, so failedRows bounds the loss instead of measuring it. Nothing else
        // in this class reads log output, so without this the wording could regress to the old
        // "dropping the batch" claim with every test still green.
        attemptOnePoisonedBatchOfTwo();

        // Asserted only after stop() has joined the flusher. The capture itself is safe to read
        // under a live producer since #735 — LogCapture hands out a CopyOnWriteArrayList — but the
        // negative assertion below is over *everything* the flusher said, and a flusher still
        // running has not finished saying it.
        this.repository.stop();

        Assertions.assertThat(this.logEvents.list)
                .as("the insert failure names the batch size, who is not retrying, that rows may "
                        + "be committed, and carries the cause")
                .anySatisfy(event -> {
                    Assertions.assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                    Assertions.assertThat(event.getFormattedMessage())
                            .contains("Failed to persist a batch of 2 flows")
                            .contains("flusher does not retry")
                            .contains("some may be committed");
                    // The stack trace is the operator's only clue why the server refused the
                    // insert; the message alone never says.
                    Assertions.assertThat(event.getThrowableProxy())
                            .as("the cause is attached")
                            .isNotNull();
                });
        // Deliberately over every captured event, not just the insert-failure line: a second
        // statement restating the loss claim beside the correct one would mislead just as badly.
        // Nothing is dropped in this scenario — the queue holds 1000 and sees 2 rows — so loss
        // vocabulary anywhere here can only be the flusher describing an outcome it cannot know.
        Assertions.assertThat(this.logEvents.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .as("no flusher log claims the rows are gone")
                .allSatisfy(message -> Assertions.assertThat(message)
                        .doesNotContainIgnoringCase("drop", "discard", "lost", "gone"));
    }

    /**
     * Arrange the deterministic poison batch both tests above need: two rows queued before
     * {@code start()}, so the flusher finds them waiting and drains them as one batch, which the
     * delegate then refuses. Returns once that insert has been attempted.
     */
    private void attemptOnePoisonedBatchOfTwo() throws Exception {
        this.delegate.failuresRemaining.set(1);
        this.repository = repository(batchConfig(2, Duration.ofMillis(600)));

        this.repository.persist(flows(2));
        this.repository.start();
        // Polls an AtomicInteger, not the appender's list: the condition must be safe to read
        // while the flusher thread is running.
        await(Duration.ofSeconds(3), "poison batch attempted", () -> this.delegate.inserts.get() == 1);
    }

    @Test
    void stopDrainsAllAcceptedRowsAndRejectsNewOnes() throws Exception {
        // maxRows unreachable and maxLatency short enough for the drain to converge in-grace.
        final var config = batchConfig(10_000, Duration.ofMillis(300));
        config.setShutdownGracePeriod(Duration.ofSeconds(2));
        this.repository = repository(config);
        this.repository.start();

        for (final var flow : flows(25)) {
            this.repository.persist(List.of(flow));
        }
        this.repository.stop();

        // stop() is synchronous: once it returns, every accepted row has been delivered.
        Assertions.assertThat(this.delegate.count()).isEqualTo(25);

        // After stop() the repository rejects (and counts) instead of accepting silently.
        this.repository.persist(flows(1));
        Assertions.assertThat(droppedRows()).isEqualTo(1);
        Assertions.assertThat(this.delegate.count()).isEqualTo(25);
    }

    @Test
    void stopIsIdempotentAndStopsTheDelegateOnlyOnce() throws Exception {
        this.repository = repository(batchConfig(10, Duration.ofMillis(100)));
        this.repository.start();
        this.repository.persist(flows(2));

        this.repository.stop();
        this.repository.stop();

        Assertions.assertThat(this.delegate.count()).isEqualTo(2);
        Assertions.assertThat(this.delegate.stops.get()).isEqualTo(1);
    }

    @Test
    void startAfterStopFailsLoudInsteadOfSilentlyDropping() {
        this.repository = repository(batchConfig(10, Duration.ofMillis(100)));
        this.repository.start();
        this.repository.stop();

        Assertions.assertThatThrownBy(this.repository::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be restarted");
    }

    @Test
    void secondStartFailsInsteadOfOrphaningTheFirstFlusher() {
        this.repository = repository(batchConfig(10, Duration.ofMillis(100)));
        this.repository.start();

        Assertions.assertThatThrownBy(this.repository::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already started");
    }

    @Test
    void concurrentProducersLoseNoFlowsAndAreBatchedTogether() throws Exception {
        // The production shape: 2 × cores parser threads call persist() concurrently. Every flow
        // must end up either delivered or counted as dropped — never silently gone.
        final int threads = 4;
        final int perThread = 250;
        this.repository = repository(batchConfig(500, Duration.ofMillis(200)));
        this.repository.start();

        final var start = new CountDownLatch(1);
        final var done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            final var worker = new Thread(() -> {
                try {
                    start.await();
                    for (final var flow : flows(perThread)) {
                        this.repository.persist(List.of(flow));
                    }
                } catch (final Exception e) {
                    throw new IllegalStateException(e);
                } finally {
                    done.countDown();
                }
            }, "producer-" + t);
            worker.setDaemon(true);
            worker.start();
        }
        start.countDown();
        Assertions.assertThat(done.await(10, TimeUnit.SECONDS))
                .as("producers finished").isTrue();

        // stop() drains synchronously, so the tally is complete once it returns.
        this.repository.stop();

        final int total = threads * perThread;
        // Equality, deliberately, and it is a property of this fixture rather than of the system:
        // ObservableRepository either stores the whole batch or stores none of it, so no row can
        // be both delivered and counted failed. Against a real server it can be: a refused insert
        // may commit a prefix, and those rows would land in delegate.count() while the whole batch
        // is charged to failedRows — pushing the sum above the total, not below it. Do not relax
        // this to isGreaterThanOrEqualTo to accommodate that; the fake cannot produce it, so the
        // relaxation would only stop catching silent loss, which is what this assertion is for.
        Assertions.assertThat(this.delegate.count() + droppedRows() + failedRows())
                .as("every flow is either delivered or counted")
                .isEqualTo(total);
        // Batching actually happened: far fewer inserts than flows.
        Assertions.assertThat(this.delegate.inserts.get()).isLessThan(total);
    }

    @Test
    void stopBeforeStartSweepsQueuedRowsWithoutFailing() throws Exception {
        // Never started: no flusher exists, so only stop()'s leftover sweep can deliver — the
        // same path that catches rows offered between the flusher's final drain and stop().
        this.repository = repository(batchConfig(10, Duration.ofMillis(100)));
        this.repository.persist(flows(3));

        this.repository.stop();

        Assertions.assertThat(this.delegate.count()).isEqualTo(3);
        Assertions.assertThat(this.delegate.inserts.get()).isEqualTo(1);
    }

    @Test
    void leftoverSweepFailureIsCountedInsteadOfThrown() throws Exception {
        this.delegate.failuresRemaining.set(1);
        this.repository = repository(batchConfig(10, Duration.ofMillis(100)));
        this.repository.persist(flows(2));

        this.repository.stop();

        Assertions.assertThat(this.delegate.count()).isZero();
        Assertions.assertThat(failedRows()).isEqualTo(2);
    }

    @Test
    void wedgedDelegateDoesNotEarnASecondInsertAfterTheGracePeriod() throws Exception {
        // The flusher is stuck in an insert that outlives the grace period. The leftover sweep
        // must not attempt another blocking insert against the same wedged delegate: the client
        // has no socket timeout by default, so that would hang shutdown indefinitely — past the
        // service manager's stop timeout — for rows that are unlikely to land anyway.
        this.delegate.block();
        this.repository = repository(batchConfig(1, Duration.ofMillis(100)));
        this.repository.start();
        this.repository.persist(flows(1));
        await(Duration.ofSeconds(2), "flusher blocked inside the delegate",
                () -> this.delegate.inserts.get() == 1);
        this.repository.persist(flows(3));

        this.repository.stop();

        Assertions.assertThat(this.delegate.inserts.get())
                .as("no sweep insert once the grace period is exhausted")
                .isEqualTo(1);
        Assertions.assertThat(failedRows())
                .as("the undelivered leftovers are counted, not silently lost")
                .isGreaterThanOrEqualTo(3);
    }

    @Test
    void rejectsNonPositiveMaxRows() {
        final var config = batchConfig(0, Duration.ofMillis(100));
        Assertions.assertThatThrownBy(() -> repository(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-rows");
    }

    @Test
    void rejectsNonPositiveQueueCapacity() {
        final var config = batchConfig(10, Duration.ofMillis(100));
        config.setQueueCapacity(0);
        Assertions.assertThatThrownBy(() -> repository(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("queue-capacity");
    }

    @Test
    void rejectsShutdownGracePeriodUnderTwiceMaxLatency() {
        // Merely-greater is not enough: one full drain window can pass before the drain starts,
        // so 4900ms/5000ms would leave 100ms to empty the whole queue.
        final var config = batchConfig(10, Duration.ofMillis(4900));
        config.setShutdownGracePeriod(Duration.ofSeconds(5));
        Assertions.assertThatThrownBy(() -> repository(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least twice max-latency");
    }

    private BatchingFlowRepository repository(final ClickhouseConfig.BatchConfig config) {
        return new BatchingFlowRepository(this.delegate, config, this.metricRegistry);
    }

    private long droppedRows() {
        return this.metricRegistry.counter(MetricRegistry.name("persister", "batch", "droppedRows")).getCount();
    }

    private long failedRows() {
        return this.metricRegistry.counter(MetricRegistry.name("persister", "batch", "failedRows")).getCount();
    }

    private static ClickhouseConfig.BatchConfig batchConfig(final int maxRows, final Duration maxLatency) {
        final var config = new ClickhouseConfig.BatchConfig();
        config.setMaxRows(maxRows);
        config.setMaxLatency(maxLatency);
        config.setQueueCapacity(1_000);
        // Validation requires grace >= 2 × maxLatency; keep the margin tight so tests that leave
        // the flusher parked in an empty drain window on stop() do not burn a production grace.
        config.setShutdownGracePeriod(maxLatency.multipliedBy(3));
        return config;
    }

    private static List<EnrichedFlow> flows(final int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> EnrichedFlow.builder().srcPort(i).build())
                .toList();
    }

    /**
     * Deadline-based polling; fails the test on timeout. Never a bare sleep.
     *
     * <p>Deliberately still a deadline, and deliberately not converted to the progress-based wait
     * the e2e tier moved to (#547, #662). Its callers split into two groups that want opposite
     * treatment, so converting the helper wholesale would break one to help the other:</p>
     *
     * <ul>
     *   <li><b>The deadline is the assertion</b> in
     *       {@link #flushesWhenBatchSizeIsReachedBeforeMaxLatency()}: a timeout deliberately shorter
     *       than {@code maxLatency} is the only thing showing the size trigger fired rather than the
     *       timer. A wait that tolerated slowness would pass on either, so it would stop testing
     *       what it names.</li>
     *   <li><b>The deadline is only a bound</b> at the other call sites, which do carry the
     *       ambiguity #547 was filed about: a slow runner and a broken flush time out alike. The
     *       exposure is much smaller here than in e2e, though, because the delegate is in-process
     *       with no container and no network, and the budgets are seconds against timers of a few
     *       hundred milliseconds or in-memory work.</li>
     * </ul>
     *
     * <p>If this is revisited, treat the two groups separately rather than alike.</p>
     */
    private static void await(final Duration timeout, final String description, final BooleanSupplier condition)
            throws InterruptedException {
        final var deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        Assertions.fail("Timed out after %s waiting for %s".formatted(timeout, description));
    }

    /**
     * Delegate around a {@link TestRepository} (which cannot be extended here — its persist
     * declares no checked exceptions) that additionally counts inserts and stops, and can fail
     * or block on demand.
     */
    private static final class ObservableRepository implements FlowRepository {

        private final TestRepository store = new TestRepository(new MetricRegistry());

        private final AtomicInteger inserts = new AtomicInteger();

        private final AtomicInteger stops = new AtomicInteger();

        private final AtomicInteger failuresRemaining = new AtomicInteger();

        /**
         * Arms an {@link Error} rather than an exception. {@code flush()} catches only
         * {@code FlowException | IOException | RuntimeException}, so this is how a failure reaches
         * the flush loop's {@code catch (Throwable)} with an insert genuinely in flight.
         */
        private final AtomicInteger errorsRemaining = new AtomicInteger();

        private volatile CountDownLatch blockOn;

        long count() {
            return this.store.count();
        }

        void block() {
            this.blockOn = new CountDownLatch(1);
        }

        void unblock() {
            final var latch = this.blockOn;
            if (latch != null) {
                latch.countDown();
            }
        }

        @Override
        public void persist(final List<EnrichedFlow> flows) throws FlowException {
            this.inserts.incrementAndGet();
            final var latch = this.blockOn;
            if (latch != null) {
                try {
                    latch.await();
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new FlowException(e);
                }
            }
            // Decrement only while positive: a plain getAndDecrement() would also count down on
            // every successful persist, so a later-armed failure would silently disarm.
            if (this.errorsRemaining.getAndUpdate(remaining -> remaining > 0 ? remaining - 1 : remaining) > 0) {
                throw new AssertionError("insert died mid-flight");
            }
            if (this.failuresRemaining.getAndUpdate(remaining -> remaining > 0 ? remaining - 1 : remaining) > 0) {
                throw new FlowException("poison batch");
            }
            this.store.persist(flows);
        }

        @Override
        public void stop() {
            this.stops.incrementAndGet();
        }
    }
}
