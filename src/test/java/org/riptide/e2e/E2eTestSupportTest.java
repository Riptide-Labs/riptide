/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.e2e;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

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
     * A dip is treated as no progress, not as a failure (#662).
     *
     * <p>The dip this was filed for came from {@code Nl6Container.sentRecords} answering {@code 0}
     * when no collector matched the protocol, and is now absorbed at the instrument by
     * {@code Nl6Container.ledger}. This stays as the backstop: a supplier that dips once must not
     * fail a run, because hard-failing on it turned an nl6 status hiccup into an ingest failure,
     * which is the false report #547 exists to remove, reintroduced one layer down.</p>
     */
    @Test
    void aSingleDipDoesNotFailARunThatRecovers() throws Exception {
        final var reads = new AtomicInteger();
        // 1, 2, then a spurious 0, then on to the target.
        final LongSupplier flaky = () -> {
            final int n = reads.incrementAndGet();
            return n == 3 ? 0L : n;
        };

        E2eTestSupport.awaitCount(STALL, POLL, "a count with one bad read", flaky, 6);

        assertThat(reads.get())
                .as("the dip must cost exactly one poll: the wait continues past it and returns on the"
                        + " read that reaches the target")
                .isEqualTo(6);
    }

    /**
     * A count that shrinks and stays down still fails, and says a decrease was seen.
     *
     * <p>Tolerating a dip must not mean tolerating a supplier that is not measuring accumulated
     * work. It stalls out instead, and the message names the reason so the failure is not misread
     * as slow ingest.</p>
     */
    @Test
    void aCountThatKeepsShrinkingStallsAndNamesTheDecrease() {
        final var shrinking = new AtomicLong(50);

        assertThatThrownBy(() -> E2eTestSupport.awaitCount(STALL, POLL, "a shrinking count",
                shrinking::decrementAndGet, 100))
                .isInstanceOf(AssertionError.class)
                // the first read already decrements: 50 -> 49 seeds "best", which stays the high-water mark
                .hasMessageContaining("Stalled at 49 of 100")
                .hasMessageContaining("decreased since its last advance");
    }

    /**
     * The cap carries the same decrease note as the stall: a count that crawls and then dips is not
     * misread as a slow ingest either.
     */
    @Test
    void aCrawlThatDipsBeforeTheCapSaysSoAtTheCap() {
        final var reads = new AtomicInteger();
        final var began = Instant.now();
        // Advances every poll until 4.3 budgets in, then reads 0. The cap fires at 5 budgets, thirty
        // polls before the stall would (last advance + 1), so the cap is the branch that reports.
        final LongSupplier crawlThenDip = () -> Duration.between(began, Instant.now())
                .compareTo(STALL.multipliedBy(43).dividedBy(10)) < 0 ? reads.incrementAndGet() : 0L;

        assertThatThrownBy(() -> E2eTestSupport.awaitCount(STALL, POLL, "a crawl that dipped",
                crawlThenDip, Long.MAX_VALUE))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Cap of 5 x PT1S reached at")
                .hasMessageContaining("decreased since its last advance");
    }

    /**
     * The decrease note is cleared by an advance. A hiccup early in the wait must not be blamed for
     * a genuine stall later, or the reader is steered at nl6 status when the ingest stopped.
     */
    @Test
    void aDipBeforeAnAdvanceIsNotBlamedForALaterStall() {
        final var reads = new AtomicInteger();
        // 1, a spurious 0, up to 3, then frozen at 3.
        final LongSupplier dipRecoverFreeze = () -> {
            final int n = reads.incrementAndGet();
            return n == 2 ? 0L : Math.min(n, 3);
        };

        assertThatThrownBy(() -> E2eTestSupport.awaitCount(STALL, POLL, "a recovered count",
                dipRecoverFreeze, 9))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Stalled at 3 of 9")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("decreased"));
    }

    /**
     * The four-argument overload — the one every e2e call site uses — forwards its arguments.
     *
     * <p>Both parameters are {@code Duration}, so transposing them in the delegation compiles
     * cleanly, and a mutation doing exactly that survived the whole suite: every other test here
     * calls the five-argument form. Under it the e2e tier runs with a two-second stall budget and a
     * minutes-long poll, so the first read lands after the budget expired and every wait fails
     * {@code Stalled at 0 of N} — while this class stayed green.</p>
     *
     * <p>Asserted through the message, which names the budget it was given: a transposed delegation
     * reports the poll interval as the budget where this expects {@link #STALL}, which it passed.
     * This test sleeps the real poll interval on purpose; that is what makes the swap visible.</p>
     */
    @Test
    void theDefaultPollOverloadForwardsTheBudgetAsTheBudget() {
        final var frozen = new AtomicLong(3);

        assertThatThrownBy(() -> E2eTestSupport.awaitCount(
                STALL, "a frozen count on the default poll", frozen::get, 9))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Stalled at 3 of 9")
                .hasMessageContaining("for " + STALL);
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
