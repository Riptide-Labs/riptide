/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.snmp4j.util.TableEvent;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The walk bounds (#536). snmp4j bounds each round-trip; nothing in snmp4j bounds how many
 * round-trips a table walk takes, so duration and heap were agent-controlled — and
 * registration follows flows, so in a credentialed wide range "agent" includes any address
 * that can emit a spoofed flow. The collector owns both bounds; these tests drive it the
 * way snmp4j's dispatcher does.
 */
class WalkBoundsTest {

    /**
     * Mocked rather than constructed: TableEvent extends EventObject, whose constructor
     * demands the TableUtils-internal request as a non-null source. The collector reads
     * only isError(), and what it counts is deliveries — which is exactly what an endless
     * agent produces.
     */
    private static TableEvent row(final int index) {
        final TableEvent event = Mockito.mock(TableEvent.class);
        Mockito.when(event.isError()).thenReturn(false);
        // production row events always carry an index (snmp4j delivers one event per row);
        // stubbing it keeps the fixture honest if the collector ever starts reading it
        Mockito.when(event.getIndex()).thenReturn(new org.snmp4j.smi.OID(new int[] {index}));
        return event;
    }

    private static TableEvent error() {
        final TableEvent event = Mockito.mock(TableEvent.class);
        Mockito.when(event.isError()).thenReturn(true);
        return event;
    }

    /**
     * The endless-increasing-table defense: an agent fabricating ifIndex 1, 2, 3, ... with
     * each response inside the per-request timeout used to walk forever while the collected
     * list grew without bound. The collector stops accepting at the cap and unblocks the
     * waiting walker immediately.
     */
    @Test
    void anEndlessTableStopsAtTheRowCapAndUnblocksTheWaiter() throws Exception {
        final var collector = new SnmpUtils.WalkCollector(100);

        int accepted = 0;
        for (int i = 1; i <= 10_000; i++) {
            if (!collector.next(row(i))) {
                break;
            }
            accepted++;
        }

        // the cap stopped delivery: snmp4j sees false and stops issuing requests. The
        // bound is exclusive, so the trip happens on the cap-exceeding row — one row past
        // the cap is retained as the detector, and the whole list is discarded anyway
        assertThat(accepted).isEqualTo(100);
        assertThat(collector.capped()).isTrue();
        assertThat(collector.events()).hasSize(101);
        // the waiter is released without any finished() call: the cap is the terminal event
        assertThat(collector.await(Duration.ofMillis(1).toNanos())).isTrue();
        assertThat(collector.isFinished()).isTrue();
    }

    /**
     * The boundary is exact: a device whose table completes at exactly the cap is a clean
     * walk, not an abandonment. The first version capped on the cap-th row itself, so a
     * complete boundary table was discarded and the endpoint backed off forever, with a
     * warn whose both explanations ("keeps growing" / "carries more") were untrue.
     */
    @Test
    void aTableCompletingAtExactlyTheCapFinishesClean() throws Exception {
        final var collector = new SnmpUtils.WalkCollector(100);
        for (int i = 1; i <= 100; i++) {
            assertThat(collector.next(row(i))).as("row %d is within the bound", i).isTrue();
        }
        collector.finished(Mockito.mock(TableEvent.class));

        assertThat(collector.await(Duration.ofSeconds(1).toNanos())).isTrue();
        assertThat(collector.capped()).as("exactly-at-cap is complete, not capped").isFalse();
        assertThat(collector.events()).hasSize(100);
    }

    /**
     * The true-hang defense: if snmp4j never delivers another event (undelivered response,
     * dead dispatcher), the wait is ours and expires. Late deliveries after abandonment are
     * refused, so snmp4j stops rather than filling a list nobody will read.
     */
    @Test
    void aWalkNobodyFinishesExpiresAndRefusesLateDeliveries() {
        final var collector = new SnmpUtils.WalkCollector(100);
        collector.next(row(1));

        final long start = System.nanoTime();
        assertThat(collector.await(Duration.ofMillis(50).toNanos())).isFalse();
        assertThat(System.nanoTime() - start).isLessThan(Duration.ofSeconds(5).toNanos());

        // abandoned: the dispatcher's next delivery is refused and the walk stops
        assertThat(collector.next(row(2))).isFalse();
        assertThat(collector.isFinished()).isTrue();
    }

    /** An exhausted budget does not wait at all: the fallback walk after a slow ifXTable walk. */
    @Test
    void anAlreadyExpiredBudgetDoesNotWait() {
        final var collector = new SnmpUtils.WalkCollector(100);
        final long start = System.nanoTime();
        assertThat(collector.await(-1)).isFalse();
        assertThat(System.nanoTime() - start).isLessThan(Duration.ofSeconds(1).toNanos());
        assertThat(collector.isFinished()).isTrue();
    }

    /** The dispatcher thread finishing normally releases the waiter with the rows intact. */
    @Test
    void aNormalFinishReleasesTheWaiterWithItsRows() throws Exception {
        final var collector = new SnmpUtils.WalkCollector(100);
        final CountDownLatch delivered = new CountDownLatch(1);
        final Thread dispatcher = new Thread(() -> {
            collector.next(row(1));
            collector.next(row(2));
            // a normal finish event carries status only, no row index; a finish event
            // WITH an index is the last-row-piggyback shape, which finished() must keep
            collector.finished(Mockito.mock(TableEvent.class));
            delivered.countDown();
        }, "fake-snmp4j-dispatcher");
        dispatcher.start();

        assertThat(collector.await(Duration.ofSeconds(5).toNanos())).isTrue();
        assertThat(delivered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(collector.capped()).isFalse();
        assertThat(collector.events()).hasSize(2);
        dispatcher.join(5_000);
    }

    /** An error event is terminal through next() too, and is kept for outcome mapping. */
    @Test
    void anErrorEventIsTerminalAndPreserved() {
        final var collector = new SnmpUtils.WalkCollector(100);
        final TableEvent error = error();

        assertThat(collector.next(error)).isFalse();
        assertThat(collector.capped()).as("an error is not the cap").isFalse();
        assertThat(collector.await(Duration.ofSeconds(1).toNanos())).isTrue();
        assertThat(collector.events()).hasSize(1);
        assertThat(collector.events().getFirst().isError()).isTrue();
    }

    /**
     * The deadline and the agent race to finish a walk, and exactly one of them may complete it.
     * An abandonment that wins must leave a late {@code finished} completing nothing.
     */
    @Test
    void anAbandonedWalkIgnoresALateFinish() {
        final AtomicInteger completions = new AtomicInteger();
        final var collector = new SnmpUtils.WalkCollector(100, done -> completions.incrementAndGet());
        collector.next(row(1));

        assertThat(collector.abandon()).as("the deadline got there first").isTrue();
        collector.finished(Mockito.mock(TableEvent.class));

        assertThat(completions.get()).as("the late finish completes nothing").isZero();
        assertThat(collector.abandon()).as("a second abandonment is not a second winner").isFalse();
    }

    /** And the other way round: a finished walk cannot be abandoned after the fact. */
    @Test
    void aFinishedWalkCannotBeAbandoned() {
        final AtomicInteger completions = new AtomicInteger();
        final var collector = new SnmpUtils.WalkCollector(100, done -> completions.incrementAndGet());
        collector.next(row(1));
        collector.finished(Mockito.mock(TableEvent.class));

        assertThat(completions.get()).isEqualTo(1);
        assertThat(collector.abandon()).as("the agent finished first; the deadline must complete nothing").isFalse();
    }

    /**
     * The true-hang defense without a waiting thread: an agent that never answers (here, a
     * session that never sends) still completes the walk, from the deadline timer, as ABANDONED.
     */
    @Test
    void aWalkNobodyAnswersCompletesAbandonedAtItsDeadline() throws Exception {
        final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        try {
            final var walk = SnmpUtils.walkRawAsync(Mockito.mock(org.snmp4j.Snmp.class),
                    Mockito.mock(org.snmp4j.Target.class), null,
                    new org.snmp4j.smi.OID[] {new org.snmp4j.smi.OID("1.3.6.1.2.1.31.1.1.1.1")}, 10,
                    System.nanoTime() + Duration.ofMillis(50).toNanos(), timer);

            assertThat(walk.get(5, TimeUnit.SECONDS).outcome()).isEqualTo(SnmpUtils.WalkOutcome.ABANDONED);
            assertThat(walk.get().rows()).isEmpty();
        } finally {
            timer.shutdownNow();
        }
    }
}
