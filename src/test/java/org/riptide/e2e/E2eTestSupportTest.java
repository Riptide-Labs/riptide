/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.e2e;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * What {@code awaitCount} waits on, and what it refuses to wait on (#547).
 *
 * <p>The e2e awaits it replaced measured an absolute deadline, so a busy runner and a broken ingest
 * failed identically. That is how a contended CI box reported an ingest regression that was not
 * one. The distinction the replacement draws is <em>progress</em>, and a green run proves nothing
 * about it: a healthy suite passes under either rule. These are the tests that fail without it.</p>
 *
 * <p>Unit tests, deliberately. The behaviour is a property of the helper, not of ClickHouse, and
 * pinning it here means {@code make jar} catches a regression that {@code make e2e} would otherwise
 * be the only gate for.</p>
 */
@Timeout(30)
class E2eTestSupportTest {

    /**
     * A hundred polls wide, so one scheduler or GC pause between two polls does not read as a stall
     * and this class does not flake on the contended runners the helper exists to tolerate. The
     * advancing test has a second margin, the cap; see its own comment.
     */
    private static final Duration STALL = Duration.ofSeconds(1);
    private static final Duration POLL = Duration.ofMillis(10);

    /**
     * A count that keeps advancing is waited on for as long as it takes, past the stall budget.
     *
     * <p>The whole point of #547: slowness is not failure. This advances one step per poll and needs
     * far longer than {@link #STALL} in total, which the deadline it replaced would have failed.</p>
     */
    @Test
    void aSlowButAdvancingCountIsNotFailed() throws Exception {
        final var counter = new AtomicLong();
        // 1.2s nominal against a 5s cap, so the runner may be 4x slower before the cap misfires.
        // The > STALL side cannot fail from slowness: sleeps only ever overshoot.
        final long target = 120;

        final long startedAt = System.nanoTime();
        E2eTestSupport.awaitCount(STALL, POLL, "a count that keeps advancing",
                counter::incrementAndGet, target);
        final var elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(counter.get()).isGreaterThanOrEqualTo(target);
        assertThat(elapsed)
                .as("the wait must have outlasted the stall budget, or this proves nothing about"
                        + " tolerating slowness; it would have passed under a deadline too")
                .isGreaterThan(STALL);
    }

    /**
     * A count that advances but never arrives is cut off at the cap, and the failure says so.
     *
     * <p>The other half of the bargain: tolerating slowness must not mean a degraded ingest crawls
     * until the CI job is cancelled. The message names the cap and when the count last moved,
     * rather than a stall, because it was not one.</p>
     */
    @Test
    void aCrawlingCountIsCutOffAtTheCapAndSaysSo() {
        final var crawling = new AtomicLong();

        assertThatThrownBy(() -> E2eTestSupport.awaitCount(STALL, POLL, "a crawling count",
                crawling::incrementAndGet, Long.MAX_VALUE))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Cap of 5 x PT1S reached at")
                .hasMessageContaining("last advance")
                .hasMessageContaining("a crawling count");
    }

    /** A frozen count fails, and says what it was stuck at rather than only that time ran out. */
    @Test
    void aStalledCountFailsAndNamesWhereItStopped() {
        final var frozen = new AtomicLong(7);

        assertThatThrownBy(() -> E2eTestSupport.awaitCount(STALL, POLL, "a frozen count",
                frozen::get, 100))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Stalled at 7 of 100")
                .hasMessageContaining("a frozen count");
    }

    /**
     * A stall fails within its budget, so the replacement is quicker to report a real problem.
     *
     * <p>Worth pinning separately: the change would be a poor trade if it caught genuine stalls only
     * after a longer wait than the deadline it replaced.</p>
     */
    @Test
    void aStalledCountFailsWithinItsBudget() {
        final var frozen = new AtomicLong();

        final long startedAt = System.nanoTime();
        final Throwable thrown = catchThrowable(() ->
                E2eTestSupport.awaitCount(STALL, POLL, "a frozen count", frozen::get, 1));
        final var elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(thrown).isInstanceOf(AssertionError.class).hasMessageContaining("Stalled at 0 of 1");
        assertThat(elapsed)
                .as("a genuine stall must be reported within its budget, not at the cap")
                .isLessThan(STALL.multipliedBy(2));
    }

    /**
     * A count that goes backwards fails on its own terms, rather than being waited on.
     *
     * <p>Monotonicity is what makes "no progress" meaningful. A shrinking count is not accumulated
     * work, so the helper says so instead of stalling out and blaming the ingest.</p>
     */
    @Test
    void aCountThatGoesBackwardsIsRejected() {
        final var shrinking = new AtomicLong(50);

        assertThatThrownBy(() -> E2eTestSupport.awaitCount(STALL, POLL, "a shrinking count",
                shrinking::decrementAndGet, 100))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("must not go backwards")
                // the first read already decrements: 50 -> 49 seeds "best", the next read is 48
                .hasMessageContaining("actual:\n  48L")
                .hasMessageContaining("greater than or equal to:\n  49L");
    }

    /** A count already at its target returns after a single read, without sleeping a poll. */
    @Test
    void anAlreadySatisfiedCountReturnsImmediately() throws Exception {
        final var reads = new AtomicLong();

        E2eTestSupport.awaitCount(STALL, POLL, "an already-satisfied count", () -> {
            reads.incrementAndGet();
            return 10L;
        }, 10);

        assertThat(reads.get()).as("one read decides; a second one means a poll was slept first").isEqualTo(1);
    }
}
