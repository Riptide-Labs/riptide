/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.repository.clickhouse;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * A bounded wait for a ClickHouse endpoint that is not answering yet (#833).
 *
 * <p>A collector started before its backend exits on the first schema statement, and under a
 * restart policy that is a restart loop; in a containerlab topology each restart orphans the veth
 * to the router. This retries a probe for {@code riptide.clickhouse.startup-wait}, then fails with
 * a message an operator can act on from the last log line alone.</p>
 *
 * <p>It retries <em>silence</em> only. A server that answers, even with an error such as a refused
 * credential or a database that does not exist yet, ends the wait at once: the rest of
 * {@link ClickhouseRepository#start()} already diagnoses every answer with a specific message, and
 * hiding those behind a retry loop would be the misdiagnosis this exists to avoid. What counts as
 * an answer is the probe's decision, not this class's; see
 * {@code ClickhouseRepository#probeServer}.</p>
 *
 * <p>The clock and the sleeper are injected so the loop can be driven deterministically by
 * {@code StartupWaitTest}, the same split {@code ViewCreationPolicyTest} makes against
 * {@code RollupRepairIT}: policy without a server, wiring with one.</p>
 */
@Slf4j
final class StartupWait {

    /** Kept on one line, and the one literal for it in main code, so it stays greppable. */
    static final String KEY = "riptide.clickhouse.startup-wait";

    /**
     * Between attempts. A constant rather than a second key: it is the rate the documented
     * Kubernetes {@code startupProbe} polls at ({@code periodSeconds: 2} in operations.md), and
     * nothing in #833 asks for a knob here.
     */
    static final Duration INTERVAL = Duration.ofSeconds(2);

    /** What one probe found. */
    sealed interface Outcome {
        /** The server answered, with anything at all. */
        record Answered() implements Outcome {
        }

        /** Nothing answered: a refused connection, an unresolvable host, or a timeout. */
        record Silent(Throwable cause) implements Outcome {
            public Silent {
                Objects.requireNonNull(cause, "cause");
            }
        }
    }

    /** One attempt against the endpoint. */
    @FunctionalInterface
    interface Probe {
        Outcome probe() throws InterruptedException;
    }

    /** How the loop pauses between attempts; {@link Thread#sleep} in production. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final Duration window;
    private final LongSupplier nanoTime;
    private final Sleeper sleeper;

    /** Production wiring: the system clock and a real sleep. */
    StartupWait(final Duration window) {
        this(window, System::nanoTime, duration -> Thread.sleep(duration.toMillis()));
    }

    /**
     * @throws IllegalArgumentException on a null or negative window, naming the key. Zero is
     *         allowed and means one probe with no retry.
     */
    StartupWait(final Duration window, final LongSupplier nanoTime, final Sleeper sleeper) {
        if (window == null || window.isNegative()) {
            throw new IllegalArgumentException(KEY + " must not be negative (got " + window + ")");
        }
        this.window = window;
        this.nanoTime = Objects.requireNonNull(nanoTime);
        this.sleeper = Objects.requireNonNull(sleeper);
    }

    Duration window() {
        return this.window;
    }

    /**
     * Probe until the server answers or the window has elapsed.
     *
     * <p>The pause before the next attempt is the shorter of {@link #INTERVAL} and what is left of
     * the window, so the last attempt lands at the window's end rather than one interval short of
     * it: a 1 s window probes at 0 s and at 1 s, not once. A zero window probes exactly once.</p>
     *
     * @throws IllegalStateException when the window elapses without an answer, naming the endpoint,
     *         the window, the attempt count, the last cause and {@link #KEY}; or when the wait is
     *         interrupted, which says so rather than blaming the server.
     */
    void await(final String endpoint, final Probe probe) {
        final long start = this.nanoTime.getAsLong();
        int attempts = 0;
        while (true) {
            attempts++;
            final Outcome outcome;
            try {
                outcome = probe.probe();
            } catch (final InterruptedException e) {
                throw interrupted(endpoint, e);
            }
            final Duration elapsed = Duration.ofNanos(this.nanoTime.getAsLong() - start)
                    .truncatedTo(ChronoUnit.MILLIS);
            if (outcome instanceof Outcome.Answered) {
                if (attempts > 1) {
                    log.info("ClickHouse at {} answered after {} attempts ({})", endpoint, attempts, elapsed);
                }
                return;
            }
            final Throwable cause = ((Outcome.Silent) outcome).cause();
            final Duration remaining = this.window.minus(elapsed);
            if (remaining.isZero() || remaining.isNegative()) {
                throw new IllegalStateException("ClickHouse at " + endpoint + " did not answer within "
                        + this.window + " (" + attempts + " attempt(s); last cause: " + rootCause(cause) + ")."
                        + " Start it, check riptide.clickhouse.endpoint, or raise " + KEY + ".", cause);
            }
            log.warn("ClickHouse at {} did not answer (attempt {}, {} elapsed of {}): {}",
                    endpoint, attempts, elapsed, this.window, rootCause(cause));
            try {
                this.sleeper.sleep(remaining.compareTo(INTERVAL) < 0 ? remaining : INTERVAL);
            } catch (final InterruptedException e) {
                throw interrupted(endpoint, e);
            }
        }
    }

    /**
     * The innermost cause, as text. Read off a real run: the client wraps a refused connection as
     * {@code ConnectionInitiationException: Query request failed (attempt: 4, duration: 54ms,
     * queryId: null)}, which names neither the host nor the refusal; the {@code Connection refused}
     * an operator needs is three causes down. Bounded by a visited set, as every chain walk in this
     * package is, so a self-referential chain cannot spin the wait.
     */
    private static String rootCause(final Throwable thrown) {
        final Set<Throwable> seen = new LinkedHashSet<>();
        Throwable deepest = thrown;
        for (Throwable cause = thrown; cause != null && seen.add(cause); cause = cause.getCause()) {
            deepest = cause;
        }
        return deepest.toString();
    }

    /** Startup is being torn down; restore the flag and say so, the shape {@code checkSchema} uses. */
    private static IllegalStateException interrupted(final String endpoint, final InterruptedException e) {
        Thread.currentThread().interrupt();
        return new IllegalStateException("Interrupted while waiting for ClickHouse at " + endpoint + ".", e);
    }
}
