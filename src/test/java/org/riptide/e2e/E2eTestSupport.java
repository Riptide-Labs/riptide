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
     * <p>Monotonicity is the assumption that makes this sound, and it is asserted rather than
     * trusted: a count that goes backwards means the caller is not measuring accumulated work, and
     * "no progress" would then be meaningless.</p>
     */
    static void awaitCount(final Duration stallBudget, final String description,
            final LongSupplier count, final long target) throws InterruptedException {
        awaitCount(stallBudget, POLL_INTERVAL, description, count, target);
    }

    /**
     * How often {@link #awaitCount(Duration, String, LongSupplier, long)} re-reads the count. The
     * five-argument overload takes the interval explicitly so its own tests need no two-second sleep.
     */
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(2);

    /** Upper bound on the whole wait, in stall budgets: generous for a slow runner, finite for a crawl. */
    private static final int CAP_MULTIPLE = 5;

    /** As {@link #awaitCount(Duration, String, LongSupplier, long)}, with the poll interval given. */
    static void awaitCount(final Duration stallBudget, final Duration poll, final String description,
            final LongSupplier count, final long target) throws InterruptedException {
        long best = count.getAsLong();
        final var started = Instant.now();
        var lastAdvance = started;
        final var cap = started.plus(stallBudget.multipliedBy(CAP_MULTIPLE));
        while (best < target) {
            if (Instant.now().isAfter(lastAdvance.plus(stallBudget))) {
                Assertions.fail("Stalled at %d of %d for %s waiting for %s, %s after the wait began"
                        .formatted(best, target, stallBudget, description, Duration.between(started, Instant.now())));
            }
            if (Instant.now().isAfter(cap)) {
                // Says when the count last moved rather than claiming it still does: a count that
                // froze late in the window reaches this check before its stall budget runs out.
                Assertions.fail("Cap of %d x %s reached at %d of %d for %s, last advance %s ago"
                        .formatted(CAP_MULTIPLE, stallBudget, best, target, description,
                                Duration.between(lastAdvance, Instant.now())));
            }
            Thread.sleep(poll.toMillis());
            final long now = count.getAsLong();
            Assertions.assertThat(now)
                    .as("%s must not go backwards: a count that shrinks is not accumulated work, and"
                            + " waiting on its progress would mean nothing", description)
                    .isGreaterThanOrEqualTo(best);
            if (now > best) {
                best = now;
                lastAdvance = Instant.now();
            }
        }
    }

    static int freeUdpPort() {
        try (var socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        } catch (final Exception e) {
            throw new IllegalStateException("No free UDP port available", e);
        }
    }
}
