/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp;

import inet.ipaddr.IPAddressString;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.snmp4j.util.TableEvent;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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

        // the cap stopped delivery: snmp4j sees false and stops issuing requests
        assertThat(accepted).isEqualTo(99);
        assertThat(collector.capped()).isTrue();
        // the waiter is released without any finished() call: the cap is the terminal event
        assertThat(collector.await(Duration.ofMillis(1).toNanos())).isTrue();
        assertThat(collector.isFinished()).isTrue();
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
            collector.finished(row(0));
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
        assertThat(collector.await(1)).isTrue();
        assertThat(collector.events()).hasSize(1);
        assertThat(collector.events().getFirst().isError()).isTrue();
    }

    /** The budget derivation: a walk may not outlive its own cadence, and the ceiling rules. */
    @Test
    void theBudgetIsTheCadenceCappedByTheCeiling() {
        final var brisk = SnmpTest.communityV2c(new IPAddressString("10.0.0.1"), 161, "public",
                Duration.ofSeconds(30), Duration.ofMinutes(30));
        assertThat(SnmpUtils.walkBudget(brisk)).isEqualTo(Duration.ofSeconds(30));

        final var slow = SnmpTest.communityV2c(new IPAddressString("10.0.0.1"), 161, "public",
                Duration.ofDays(1), Duration.ofDays(2));
        assertThat(SnmpUtils.walkBudget(slow)).isEqualTo(SnmpUtils.WALK_BUDGET_CEILING);

        final var noCadence = SnmpTest.communityV2c(new IPAddressString("10.0.0.1"), 161, "public");
        assertThat(SnmpUtils.walkBudget(noCadence)).isEqualTo(SnmpUtils.WALK_BUDGET_CEILING);
    }
}
