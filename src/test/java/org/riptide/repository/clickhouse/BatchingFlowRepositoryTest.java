/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.repository.clickhouse;

import com.codahale.metrics.MetricRegistry;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.riptide.config.ClickhouseConfig;
import org.riptide.pipeline.EnrichedFlow;
import org.riptide.pipeline.FlowException;
import org.riptide.repository.FlowRepository;
import org.riptide.repository.TestRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
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

    @AfterEach
    void tearDown() {
        if (this.repository != null) {
            this.delegate.unblock();
            this.repository.stop();
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
    void poisonBatchIsDroppedAndSubsequentBatchesStillFlush() throws Exception {
        this.delegate.failuresRemaining.set(1);
        this.repository = repository(batchConfig(2, Duration.ofMillis(600)));

        // Queued before start(): the flusher finds both rows waiting and drains them as one
        // deterministic batch, which the delegate poisons.
        this.repository.persist(flows(2));
        this.repository.start();
        await(Duration.ofSeconds(3), "poison batch attempted", () -> this.delegate.inserts.get() == 1);

        this.repository.persist(flows(2));

        await(Duration.ofSeconds(3), "flush after the poison batch", () -> this.delegate.count() == 2);
        Assertions.assertThat(failedRows()).isEqualTo(2);
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
    void rejectsMaxLatencyNotBelowShutdownGracePeriod() {
        final var config = batchConfig(10, Duration.ofSeconds(2));
        config.setShutdownGracePeriod(Duration.ofSeconds(2));
        Assertions.assertThatThrownBy(() -> repository(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shorter than shutdown-grace-period");
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
        // Validation requires maxLatency < grace; keep the margin small so tests that leave the
        // flusher parked in an empty drain window on stop() do not burn a production-sized grace.
        config.setShutdownGracePeriod(maxLatency.plusMillis(500));
        return config;
    }

    private static List<EnrichedFlow> flows(final int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> EnrichedFlow.builder().srcPort(i).build())
                .toList();
    }

    /** Deadline-based polling; fails the test on timeout. Never a bare sleep. */
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
            if (this.failuresRemaining.getAndDecrement() > 0) {
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
