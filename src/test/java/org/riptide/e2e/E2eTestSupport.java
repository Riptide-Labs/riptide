/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.e2e;

import org.assertj.core.api.Assertions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.DatagramSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** Shared helpers for the e2e test tier. */
final class E2eTestSupport {

    private static final Logger LOG = LoggerFactory.getLogger(E2eTestSupport.class);

    private E2eTestSupport() {
    }

    /**
     * Polls until {@code count} reaches {@code target}, failing only once it <em>stops advancing</em>.
     *
     * <p>The budget measures a stall, not the whole wait (#547). Every e2e wait here is "a
     * monotonically growing count reaches a level", and under an absolute deadline a busy runner and
     * a broken ingest fail identically: both simply do not arrive in time. That is how a contended
     * CI box reported an ingest regression. Run {@code 30587496259} timed out after {@code PT2M} at
     * 1216 rows of a 1280-row ledger, having ingested the whole way and merely not finished.</p>
     *
     * <p>Waiting on progress separates them. A slow runner still advances, so it waits as long as it
     * needs; a genuine stall stops advancing and fails within one budget of its last advance. A stall
     * from the start is therefore reported no later than under the old deadline. The budgets keep
     * their old values because their meaning, not their size, was wrong.</p>
     *
     * <p>The wait is bounded twice, because one crawling wait and a suite of them fail differently.
     * A count that keeps advancing but never arrives, such as an ingest that has degraded rather
     * than died, fails after {@link #CAP_MULTIPLE} stall budgets and says so. That bound is per
     * wait, and on its own it was not enough: ten of them multiply to roughly four times the CI job
     * timeout, so a sustained crawl was cancelled with no count attached, which is the outcome this
     * paragraph used to promise it prevented (#662). {@link #SUITE_BUDGET} bounds their sum, so the
     * run still ends with a count and a reason.</p>
     *
     * <p>Monotonicity is the assumption that makes this sound, and a dip is treated as no progress
     * rather than as a failure (#662). That tolerance is a backstop rather than the fix: the source
     * that actually dipped was nl6's ledger, which answered {@code 0} with no error when no
     * collector matched the protocol. It is now polled through {@code Nl6Container.ledger}, which
     * holds its own high-water mark and never throws, so the quirk no longer reaches here. Every
     * supplier passed in should be monotonic; this branch keeps one bad read from being mistaken for
     * evidence about the ingest, which is the class of false report #547 exists to remove. A count
     * that genuinely shrinks and stays down still stalls out, and the failure says a decrease was
     * seen since the last advance so the cause is not misread as slow ingest.</p>
     */
    static void awaitCount(final Duration stallBudget, final String description,
            final LongSupplier count, final long target) throws InterruptedException {
        awaitCount(stallBudget, POLL_INTERVAL, description, count, target);
    }

    /**
     * How often {@link #awaitCount(Duration, String, LongSupplier, long)} re-reads the count. The
     * five-argument overload takes the interval explicitly so most of its tests need no two-second
     * sleep; the one pinning this delegation goes through this value on purpose.
     */
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(2);

    /** Upper bound on the whole wait, in stall budgets: generous for a slow runner, finite for a crawl. */
    private static final int CAP_MULTIPLE = 5;

    /**
     * Total time every wait in this JVM may spend between them, which is the bound the CI job
     * actually imposes (#662).
     *
     * <p>Per-wait caps cannot provide it. The ten e2e waits carry 37 minutes of stall budget, so at
     * {@link #CAP_MULTIPLE} the bound on their sum is about 185 minutes against
     * {@code timeout-minutes: 45} in {@code .github/workflows/build.yml}. No value of the multiple
     * fixes that: one that fits the job is one that no longer tolerates a slow runner, which is what
     * #547 was filed to allow. The constraint is on the sum, so the bound has to be too.</p>
     *
     * <p>Sized to leave the rest of the job its share: checkout, JDK setup, the build itself,
     * container pulls, and the ITs that do not wait on counts. Failsafe is not configured to fork
     * per class ({@code forkCount} defaults to 1 with {@code reuseForks}), so one counter spans the
     * whole e2e run, which is the thing being bounded.</p>
     */
    private static final Duration SUITE_BUDGET = Duration.ofMinutes(30);

    /** Time already spent waiting, across every call in this JVM. See {@link #SUITE_BUDGET}. */
    private static final AtomicLong SPENT_NANOS = new AtomicLong();

    /**
     * Test seam: seeds the process-wide counter so the budget can be reached without spending it.
     *
     * <p>Surefire and failsafe fork separately, so a unit test setting this cannot reach the e2e
     * run. Within a class it is shared state, and a test that sets it has to restore it.</p>
     */
    static void seedSpentForTesting(final Duration spent) {
        SPENT_NANOS.set(spent.toNanos());
    }

    /** Test seam: what the process-wide counter currently holds. See {@link #seedSpentForTesting}. */
    static Duration spentForTesting() {
        return Duration.ofNanos(SPENT_NANOS.get());
    }

    /** As {@link #awaitCount(Duration, String, LongSupplier, long)}, with the poll interval given. */
    static void awaitCount(final Duration stallBudget, final Duration poll, final String description,
            final LongSupplier count, final long target) throws InterruptedException {
        final var enteredAt = Instant.now();
        try {
            await(stallBudget, poll, description, count, target, enteredAt);
        } finally {
            // In a finally, so a wait that fails still spends its time: the budget bounds the job,
            // and a job is not made shorter by the wait that overran it having thrown.
            SPENT_NANOS.addAndGet(Duration.between(enteredAt, Instant.now()).toNanos());
        }
    }

    private static void await(final Duration stallBudget, final Duration poll, final String description,
            final LongSupplier count, final long target, final Instant enteredAt) throws InterruptedException {
        final var alreadySpent = Duration.ofNanos(SPENT_NANOS.get());
        long best = count.getAsLong();
        // Cleared on every advance, so the note means "decreased since the count last moved" rather
        // than blaming a stale hiccup for a genuine stall minutes later.
        boolean sawDecrease = false;
        final var started = enteredAt;
        var lastAdvance = started;
        // The measurement the budgets are sized from: a stall budget has to cover the longest gap
        // between advances on the slowest runner, and nothing reported that gap before (#662).
        var longestGap = Duration.ZERO;
        final var cap = started.plus(stallBudget.multipliedBy(CAP_MULTIPLE));
        while (best < target) {
            if (Instant.now().isAfter(lastAdvance.plus(stallBudget))) {
                Assertions.fail("Stalled at %d of %d for %s waiting for %s, %s after the wait began%s"
                        .formatted(best, target, stallBudget, description,
                                Duration.between(started, Instant.now()), decreaseNote(sawDecrease)));
            }
            if (Instant.now().isAfter(cap)) {
                // Says when the count last moved rather than claiming it still does: a count that
                // froze late in the window reaches this check before its stall budget runs out.
                Assertions.fail("Cap of %d x %s reached at %d of %d for %s, last advance %s ago%s"
                        .formatted(CAP_MULTIPLE, stallBudget, best, target, description,
                                Duration.between(lastAdvance, Instant.now()), decreaseNote(sawDecrease)));
            }
            final var spent = alreadySpent.plus(Duration.between(started, Instant.now()));
            if (spent.compareTo(SUITE_BUDGET) > 0) {
                // Checked here rather than after the wait, so the run ends with a count and a reason
                // while the job still has time to report them.
                Assertions.fail(("Suite wait budget of %s exhausted (%s spent) at %d of %d for %s."
                        + " The job timeout would cancel this run before it finished%s")
                        .formatted(SUITE_BUDGET, spent, best, target, description,
                                decreaseNote(sawDecrease)));
            }
            Thread.sleep(poll.toMillis());
            final long now = count.getAsLong();
            if (now < best) {
                // Recorded, not thrown: a single bad read is not evidence about the ingest, and a
                // persistent one reaches the stall branch above with this noted in its message.
                sawDecrease = true;
            }
            if (now > best) {
                final var advancedAt = Instant.now();
                final var gap = Duration.between(lastAdvance, advancedAt);
                if (gap.compareTo(longestGap) > 0) {
                    longestGap = gap;
                }
                best = now;
                lastAdvance = advancedAt;
                sawDecrease = false;
            }
        }
        // One line per completed wait, not per advance: enough to size the budgets and to tell a
        // slow-but-advancing run from a hung one afterwards, without a line every poll.
        LOG.info("Reached {} of {} for {} in {}, longest gap between advances {} against a {} budget",
                best, target, description, Duration.between(started, Instant.now()), longestGap, stallBudget);
    }

    /** The suffix both failure messages carry when a read went backwards since the last advance. */
    private static String decreaseNote(final boolean sawDecrease) {
        return sawDecrease
                ? " (the count decreased since its last advance, so it may not be measuring accumulated work)"
                : "";
    }

    static int freeUdpPort() {
        try (var socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        } catch (final Exception e) {
            throw new IllegalStateException("No free UDP port available", e);
        }
    }
}
