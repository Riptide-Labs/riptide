/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.e2e;

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
     * paragraph used to promise it prevented (#662). {@link #SUITE_BUDGET} bounds their sum, so as
     * long as the rest of the job fits in what it leaves, the run ends with a count and a reason.</p>
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
     *
     * @throws WaitFailure as {@link Stalled}, {@link CapReached} or {@link SuiteBudgetExhausted},
     *         each an {@link AssertionError}, so a failed wait fails the test that made it
     * @throws IllegalArgumentException for arguments that cannot produce a usable wait; see
     *         {@link #requireUsableArguments}
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
    static final int CAP_MULTIPLE = 5;

    /**
     * Total time every wait in this JVM may spend between them (#662).
     *
     * <p>Per-wait caps cannot bound the sum. The sixteen e2e waits at ten call sites carry 37
     * minutes of stall budget, so at {@link #CAP_MULTIPLE} the bound on their sum is about 185
     * minutes against {@code timeout-minutes: 45} in {@code .github/workflows/build.yml}. No value
     * of the multiple fixes that: one that fits the job is one that no longer tolerates a slow
     * runner, which is what #547 was filed to allow. The constraint is on the sum, so the bound has
     * to be too.</p>
     *
     * <p>This bounds only time inside {@code awaitCount}. Checkout, JDK setup, the build, container
     * pulls, the ITs that do not wait on counts, and each read of the count are all outside it.
     * The 15 minutes left to them is a judgement, not a measurement: the last full e2e job took
     * 6m24s, and the split has not been checked against a slow run. Failsafe is not configured to
     * fork per class ({@code forkCount} defaults to 1 with {@code reuseForks}; the plugin block in
     * {@code pom.xml} says so), so one counter spans the whole e2e run.</p>
     */
    static final Duration SUITE_BUDGET = Duration.ofMinutes(30);

    /** Time already spent waiting, across every call in this JVM. See {@link #SUITE_BUDGET}. */
    private static final AtomicLong SPENT_NANOS = new AtomicLong();

    /**
     * Test seam: seeds the process-wide counter so the budget can be reached without spending it.
     *
     * <p>Surefire and failsafe fork separately, so a unit test setting this cannot reach the e2e
     * run. Within a class it is shared state, and a test that sets it has to restore what
     * {@link #spentForTesting} held before.</p>
     */
    static void seedSpentForTesting(final Duration spent) {
        SPENT_NANOS.set(spent.toNanos());
    }

    /** Test seam: what the process-wide counter currently holds. See {@link #seedSpentForTesting}. */
    static Duration spentForTesting() {
        return Duration.ofNanos(SPENT_NANOS.get());
    }

    /**
     * What a completed wait measured. {@code longestGap} is the longest time between two advances
     * (the first measured from entry), which is the number a stall budget has to cover on the
     * slowest runner; nothing reported it before (#662).
     */
    record Wait(long count, Duration elapsed, Duration longestGap) {
    }

    /**
     * Why a wait failed, carried as a type rather than only as message text (#662).
     *
     * <p>The three failures mean different things to whoever reads the run. A stall says the count
     * stopped moving, a cap says it moved the whole time and never arrived, and an exhausted suite
     * budget usually says some <em>earlier</em> wait overran and this one inherited the shortfall.
     * Separated only by message text, nothing could tell them apart programmatically, and a test
     * asserting on the wording of one was really asserting on constants inside the other.</p>
     *
     * <p>Still an {@link AssertionError}, so a failing wait keeps reading as a failed assertion to
     * JUnit and to every existing {@code isInstanceOf(AssertionError.class)}.</p>
     */
    abstract static sealed class WaitFailure extends AssertionError
            permits Stalled, CapReached, SuiteBudgetExhausted {
        WaitFailure(final String message) {
            super(message);
        }
    }

    /** The count stopped advancing for a whole stall budget. */
    static final class Stalled extends WaitFailure {
        Stalled(final String message) {
            super(message);
        }
    }

    /** The count kept advancing but never arrived, so the per-wait cap cut it off. */
    static final class CapReached extends WaitFailure {
        CapReached(final String message) {
            super(message);
        }
    }

    /** The waits have used their whole share of the job timeout. See {@link #SUITE_BUDGET}. */
    static final class SuiteBudgetExhausted extends WaitFailure {
        SuiteBudgetExhausted(final String message) {
            super(message);
        }
    }

    /**
     * As {@link #awaitCount(Duration, String, LongSupplier, long)}, with the poll interval given.
     *
     * @throws WaitFailure as {@link Stalled}, {@link CapReached} or {@link SuiteBudgetExhausted}
     * @throws IllegalArgumentException for arguments that cannot produce a usable wait; see
     *         {@link #requireUsableArguments}
     */
    static Wait awaitCount(final Duration stallBudget, final Duration poll, final String description,
            final LongSupplier count, final long target) throws InterruptedException {
        requireUsableArguments(stallBudget, poll, target);
        // Monotonic, so a wall-clock step during the wait cannot credit the budget.
        final long enteredNanos = System.nanoTime();
        try {
            return await(stallBudget, poll, description, count, target, enteredNanos);
        } finally {
            // In a finally, so a wait that fails still spends its time: the budget bounds the job,
            // and a job is not made shorter by the wait that overran it having thrown.
            SPENT_NANOS.addAndGet(System.nanoTime() - enteredNanos);
        }
    }

    /**
     * Refuses the argument combinations that wait without ever being able to report anything (#662).
     *
     * <p>Each of these used to produce a wait that looked like a wait and was not one, which is the
     * same reader-misleading failure the rest of this helper exists to remove. A poll no shorter
     * than the budget reads at most once per budget: a frozen count stalls after a single read, and
     * an advancing one is cut off by the cap after {@link #CAP_MULTIPLE} reads unless the target
     * happens to arrive within them. Either way the wait observes almost nothing about the ingest.
     * That is not hypothetical: the transposed-delegation mutation hands the e2e sites minute-long
     * polls against a two-second budget. A non-positive budget stalls on the first check. A
     * non-positive target is satisfied before the first read, so the call returns immediately while
     * reading in the test as though something was waited for.</p>
     *
     * <p>{@link IllegalArgumentException}, deliberately, not a {@link WaitFailure}: these are the
     * caller's mistake, not an observation about the system under test, and they should not be
     * catchable as a wait that failed.</p>
     */
    private static void requireUsableArguments(final Duration stallBudget, final Duration poll, final long target) {
        if (stallBudget.isNegative() || stallBudget.isZero()) {
            throw new IllegalArgumentException(
                    "stall budget must be positive, got " + stallBudget + "; every wait would stall on its first check");
        }
        if (poll.isNegative() || poll.isZero()) {
            throw new IllegalArgumentException("poll interval must be positive, got " + poll);
        }
        if (poll.compareTo(stallBudget) >= 0) {
            throw new IllegalArgumentException(("poll interval %s is not shorter than the stall budget %s,"
                    + " so the wait reads at most once per budget and observes almost nothing"
                    + " about the ingest").formatted(poll, stallBudget));
        }
        if (target <= 0) {
            throw new IllegalArgumentException(
                    "target must be positive, got " + target + "; a non-positive target waits for nothing");
        }
    }

    private static Wait await(final Duration stallBudget, final Duration poll, final String description,
            final LongSupplier count, final long target, final long enteredNanos) throws InterruptedException {
        final var spentBefore = Duration.ofNanos(SPENT_NANOS.get());
        long best = count.getAsLong();
        // Cleared on every advance, so the note means "decreased since the count last moved" rather
        // than blaming a stale hiccup for a genuine stall minutes later.
        boolean sawDecrease = false;
        final var started = Instant.now();
        var lastAdvance = started;
        var lastHeartbeat = started;
        var longestGap = Duration.ZERO;
        final var cap = started.plus(stallBudget.multipliedBy(CAP_MULTIPLE));
        while (best < target) {
            if (Instant.now().isAfter(lastAdvance.plus(stallBudget))) {
                // Names both the budget it was given and the stall it measured, as the cap does, so
                // the two failures agree on what they report (#662).
                throw new Stalled(("Stalled at %d of %d for %s waiting for %s, last advance %s ago,"
                        + " %s after the wait began%s%s")
                        .formatted(best, target, stallBudget, description,
                                Duration.between(lastAdvance, Instant.now()),
                                Duration.between(started, Instant.now()), gapNote(longestGap),
                                decreaseNote(sawDecrease)));
            }
            if (Instant.now().isAfter(cap)) {
                // Says when the count last moved rather than claiming it still does: a count that
                // froze late in the window reaches this check before its stall budget runs out.
                throw new CapReached("Cap of %d x %s reached at %d of %d for %s, last advance %s ago%s%s"
                        .formatted(CAP_MULTIPLE, stallBudget, best, target, description,
                                Duration.between(lastAdvance, Instant.now()), gapNote(longestGap),
                                decreaseNote(sawDecrease)));
            }
            final var spent = spentBefore.plus(Duration.ofNanos(System.nanoTime() - enteredNanos));
            if (spent.compareTo(SUITE_BUDGET) > 0) {
                // Checked here rather than after the wait, so the run ends with a count and a reason
                // while the job still has time to report them. Names the share spent before entry,
                // because every wait after the one that overran fails here too, and only the first
                // of those failures is about its own count.
                throw new SuiteBudgetExhausted(("Suite wait budget of %s exhausted (%s spent, %s of it before this wait)"
                        + " at %d of %d for %s. The waits have used the share of the job timeout set"
                        + " aside for them%s%s")
                        .formatted(SUITE_BUDGET, spent, spentBefore, best, target, description,
                                gapNote(longestGap), decreaseNote(sawDecrease)));
            }
            if (Instant.now().isAfter(lastHeartbeat.plus(stallBudget))) {
                // Once per stall budget, not per advance: bounds the silence of a slow-but-advancing
                // wait at one budget, which is what the absolute deadline it replaced gave, without
                // a line every poll (#662).
                lastHeartbeat = Instant.now();
                LOG.info("Waiting at {} of {} for {}, {} elapsed, longest gap between advances so far {}",
                        best, target, description, Duration.between(started, lastHeartbeat), longestGap);
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
        final var wait = new Wait(best, Duration.between(started, Instant.now()), longestGap);
        // One line per completed wait: the measurement the budgets are sized from, in the log a CI
        // run leaves behind.
        LOG.info("Reached {} of {} for {} in {}, longest gap between advances {} against a {} budget",
                wait.count(), target, description, wait.elapsed(), wait.longestGap(), stallBudget);
        return wait;
    }

    /** The suffix every failure message carries: the number the stall budget is sized from. */
    private static String gapNote(final Duration longestGap) {
        return ", longest gap between advances " + longestGap;
    }

    /** The suffix every failure message carries when a read went backwards since the last advance. */
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
