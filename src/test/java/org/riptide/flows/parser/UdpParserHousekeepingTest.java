/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser;

import com.codahale.metrics.MetricRegistry;
import org.junit.jupiter.api.Test;
import org.riptide.flows.parser.netflow5.Netflow5UdpParser;
import org.riptide.pipeline.Identity;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * A parser owns the scheduler that sweeps its sessions, and releases it with itself.
 *
 * <p>This class used to carry a third test: hand {@code start()} a poisoned
 * {@code ScheduledExecutorService} and assert nothing was scheduled on it. That guard is gone
 * because #459 removed the parameter — there is no executor to pass any more, so the mistake it
 * caught cannot be made. Deleting a test is normally a loss of coverage; here the hazard became
 * structurally impossible rather than merely watched, which is the only good reason to remove one.
 *
 * <p>What it protected, for the record: the executor was the listener's event loop group, whose job
 * is draining a socket. {@code scheduleAtFixedRate} dispatches by round-robin, so a sweep placed
 * there could land on the reading thread and turn every execution into a pause in packet reception
 * — kernel loss on {@code socketDrops} rather than an application error (#457).
 */
class UdpParserHousekeepingTest {

    @Test
    void housekeepingRunsOnASchedulerTheParserOwns() {
        final var parser = parser("owned-check");

        parser.start();
        try {
            assertThat(awaitThreads("udp-parser-housekeeping-owned-check"))
                    .as("the sweep must be scheduled on a thread belonging to this parser")
                    .isNotEmpty();
        } finally {
            parser.stop();
        }

        assertThat(awaitNoThreads("udp-parser-housekeeping-owned-check"))
                .as("the scheduler is released with its owner")
                .isEmpty();
    }

    @Test
    void stoppingTwiceIsWellDefined() {
        final var parser = parser("double-stop");
        parser.start();
        parser.stop();

        assertThatCode(parser::stop).doesNotThrowAnyException();
    }

    /** Polls until the named threads appear, or the deadline passes. */
    private static List<String> awaitThreads(final String prefix) {
        return await(prefix, false);
    }

    /** Polls until the named threads are gone, or the deadline passes. */
    private static List<String> awaitNoThreads(final String prefix) {
        return await(prefix, true);
    }

    // One loop, two exit conditions: waiting for a scheduler to appear and waiting for it to be
    // released need opposite predicates, and using the appear-helper for both made every
    // disappearance assertion sit out the full deadline before passing.
    private static List<String> await(final String prefix, final boolean wantGone) {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            final var alive = Thread.getAllStackTraces().keySet().stream()
                    .map(Thread::getName)
                    .filter(name -> name.startsWith(prefix))
                    .toList();
            if (alive.isEmpty() == wantGone || System.nanoTime() >= deadline) {
                return alive;
            }
            try {
                Thread.sleep(10);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return alive;
            }
        }
    }

    private static UdpParserBase parser(final String name) {
        return new Netflow5UdpParser(name, (source, flows) -> { }, new Identity("t", "o", "z", "s"), new MetricRegistry());
    }
}
