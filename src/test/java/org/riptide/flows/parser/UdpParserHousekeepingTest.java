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
import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * A parser schedules its session housekeeping on a scheduler it owns, never on the executor its
 * listener hands over.
 *
 * <p>That executor is the listener's event loop group, whose job is draining a socket.
 * {@code scheduleAtFixedRate} dispatches by round-robin, so work placed there can land on the
 * reading thread and turn every sweep into a pause in packet reception — kernel loss on
 * {@code socketDrops} rather than an application error (#457).
 *
 * <p>Two assertions, because either alone can pass on a parser that does nothing: the handed-over
 * executor must go unused, AND the sweep must actually be scheduled somewhere.
 */
class UdpParserHousekeepingTest {

    @Test
    void nothingIsScheduledOnTheExecutorTheListenerPasses() {
        final var poisoned = new PoisonedScheduler();
        final var parser = parser("poison-check");

        parser.start(poisoned);
        try {
            assertThat(poisoned.used)
                    .as("the listener's event loop group must not be used as a timer")
                    .isFalse();
        } finally {
            parser.stop();
        }
    }

    @Test
    void housekeepingRunsOnASchedulerTheParserOwns() {
        final var parser = parser("owned-check");

        parser.start(new PoisonedScheduler());
        try {
            assertThat(awaitThreads("udp-parser-housekeeping-owned-check"))
                    .as("the sweep must be scheduled somewhere — without this the guard above "
                        + "passes on a parser that schedules nothing at all")
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
        parser.start(new PoisonedScheduler());
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

    /** Fails the test rather than the process: records any use instead of scheduling anything. */
    private static final class PoisonedScheduler implements ScheduledExecutorService {
        private volatile boolean used;

        private <T> T poison() {
            this.used = true;
            throw new UnsupportedOperationException(
                    "a parser must own its scheduler, not borrow the listener's event loop group");
        }

        @Override public ScheduledFuture<?> schedule(Runnable c, long d, TimeUnit u) { return poison(); }
        @Override public <V> ScheduledFuture<V> schedule(Callable<V> c, long d, TimeUnit u) { return poison(); }
        @Override public ScheduledFuture<?> scheduleAtFixedRate(Runnable c, long i, long p, TimeUnit u) { return poison(); }
        @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable c, long i, long d, TimeUnit u) { return poison(); }
        @Override public void execute(Runnable command) { poison(); }
        @Override public <T> java.util.concurrent.Future<T> submit(Callable<T> task) { return poison(); }
        @Override public <T> java.util.concurrent.Future<T> submit(Runnable task, T result) { return poison(); }
        @Override public java.util.concurrent.Future<?> submit(Runnable task) { return poison(); }
        @Override public <T> List<java.util.concurrent.Future<T>> invokeAll(java.util.Collection<? extends Callable<T>> t) { return poison(); }
        @Override public <T> List<java.util.concurrent.Future<T>> invokeAll(java.util.Collection<? extends Callable<T>> t, long o, TimeUnit u) { return poison(); }
        @Override public <T> T invokeAny(java.util.Collection<? extends Callable<T>> t) { return poison(); }
        @Override public <T> T invokeAny(java.util.Collection<? extends Callable<T>> t, long o, TimeUnit u) { return poison(); }
        @Override public void shutdown() { poison(); }
        @Override public List<Runnable> shutdownNow() { return poison(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return poison(); }
    }
}
