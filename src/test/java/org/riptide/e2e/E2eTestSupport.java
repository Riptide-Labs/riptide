/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.e2e;

import org.assertj.core.api.Assertions;

import java.net.DatagramSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.function.LongSupplier;

/** Shared helpers for the e2e test tier. */
final class E2eTestSupport {

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
     * <p>The wait is still bounded. A count that keeps advancing but never arrives, such as an ingest
     * that has degraded rather than died, fails after {@link #CAP_MULTIPLE} stall budgets and says
     * so. The bound is per wait, not per suite; it makes a single crawl a named failure rather than
     * a cancelled job with no count attached.</p>
     *
     * <p>Monotonicity is the assumption that makes this sound, but a dip is treated as no progress
     * rather than as a failure (#662). {@code Nl6Container.sentRecords} returns {@code 0} when no
     * collector matches the protocol, with no error — harmless under the boolean predicate this
     * replaced, which simply read false and polled again. Hard-failing on it would turn an nl6
     * status hiccup into an ingest failure, which is the class of false report #547 exists to
     * remove. A count that genuinely shrinks and stays down still stalls out, and the failure says a
     * decrease was seen since the last advance so the cause is not misread as slow ingest.</p>
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

    /** As {@link #awaitCount(Duration, String, LongSupplier, long)}, with the poll interval given. */
    static void awaitCount(final Duration stallBudget, final Duration poll, final String description,
            final LongSupplier count, final long target) throws InterruptedException {
        long best = count.getAsLong();
        // Cleared on every advance, so the note means "decreased since the count last moved" rather
        // than blaming a stale hiccup for a genuine stall minutes later.
        boolean sawDecrease = false;
        final var started = Instant.now();
        var lastAdvance = started;
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
            Thread.sleep(poll.toMillis());
            final long now = count.getAsLong();
            if (now < best) {
                // Recorded, not thrown: a single bad read is not evidence about the ingest, and a
                // persistent one reaches the stall branch above with this noted in its message.
                sawDecrease = true;
            }
            if (now > best) {
                best = now;
                lastAdvance = Instant.now();
                sawDecrease = false;
            }
        }
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
