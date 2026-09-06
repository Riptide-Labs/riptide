/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.classification.internal.decision;

import org.junit.jupiter.api.Test;
import org.riptide.classification.ClassificationRequest;
import org.riptide.classification.DefaultRule;
import org.riptide.classification.Rule;
import org.riptide.classification.internal.csv.CsvImporter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Measures how hard {@code Tree.of} works, before anyone gates it (#768).
 *
 * <p>#746 left the build quadratic and nothing observes its cost, so a change can make it several times
 * more expensive and fail no check — shown in review by deleting {@code .parallel()}, which left
 * {@code BundledRulesetTreeIdentityTest} fully green because that test pins tree <em>shape</em>, not
 * work done.
 *
 * <p><b>The scaling row is what makes this test able to fail.</b> An earlier version asserted only that
 * the count was positive and reproducible, and review proved that worthless: deleting one of two
 * counting sites cut the number by 99% and the suite stayed green, because "positive and reproducible"
 * is true of any subset of the work. A ratio across doubling input sizes is not — it only holds if the
 * count tracks the whole quadratic term.
 *
 * <p><b>This never builds the bundled ruleset.</b> {@code BundledRulesetTreeIdentityTest} warns that a
 * direct {@code Tree.of} call silently restores the second bundled build #707 removed, at 30-40 s a time
 * under the coverage agent — and the first version of this class did exactly that, twice, for 82 s. The
 * slice size is asserted below so the next version cannot do it again quietly.
 *
 * <p><b>What is gated here and what is not.</b> This slice is pinned exactly, because it is cheap and
 * deterministic. The shipped-size figure in {@code docs/docs/deploy/operations.md} (262,251,844) is not,
 * because reproducing it means building the bundled ruleset — the 30-40 s cost #707 deliberately removed.
 * Nor is any absolute <em>budget</em> asserted: research for #768 found no published account, in any
 * ecosystem, of what a deterministic cost gate costs to maintain over a year, and the one that
 * demonstrably survived years in blocking CI (Chromium's binary-size trybot, mandatory since October
 * 2018) pins a <em>per-commit delta</em> with an escape valve. Pinning a ceiling today would be guessing
 * at the tolerance; pinning today's value is just recording it, and the git history of these two
 * constants is the record #768 exists to accumulate.
 *
 * <p><b>What the number cannot see.</b> It counts (candidates-scored + 1) x rules-at-that-node — the
 * extra one being the winner's second pass, about 1% of the total. Not what happens inside a verdict,
 * not candidate enumeration or {@code distinct()}, not {@code canRestrict}, not the classifier sort in a
 * leaf — and, deliberately, not parallelism. What it can no longer miss is work the counter itself
 * fails to observe: {@link #theCountEqualsTheVerdictsActuallyPerformed} checks the total against a count
 * taken inside {@code Threshold}, which is the one blind spot the magnitudes and the ratio share.
 * A gate built on this must still not be described as covering the four above.
 */
class TreeBuildWorkCounterTest {

    /** Small enough to cost milliseconds; large enough that the build branches several levels. */
    private static final int SLICE = 200;

    /**
     * Recorded, not derived: the work a build of the first {@value #SLICE} bundled rules costs.
     *
     * <p>Baselines: {@code 284,792} and {@code 1,124,740} as of 2026-09-07.
     *
     * <p><b>Where this departs from the repo's other scale budgets.</b> {@code src/bench/README.md}
     * ships three of them ({@code lookup.trie-scale-flatness}, {@code lookup.production-scale-flatness},
     * {@code parse.direct-linearity}) and sets each threshold at roughly 3x the measured baseline,
     * because those measure nanoseconds and a tight bound would flake on a loaded machine. This one
     * asserts exact equality, and can, because the quantity is a count rather than a duration: the same
     * input does the same work on any machine, at any core count, warm or cold. The re-baselining
     * protocol is taken from that README; the tolerance is not.
     *
     * <p><b>Re-baselining.</b> These move when the build's work genuinely changes or when the first
     * {@value #SLICE} rows of {@code classification-rules.csv} are edited. Both are legitimate; what
     * is not legitimate is updating them without saying which. Run the test, take the printed
     * figures, and record the reason in the commit — the git history of these two constants is the
     * record #768 exists to accumulate.
     *
     * <p>Pinned rather than left to the ratio alone because the ratio cannot see two real errors:
     * losing the winner-site count (measured ratio 3.966, inside the band) and any constant-factor
     * error (doubling the candidate site measured 3.9575, also inside). Both change these numbers.
     */
    private static final long WORK_AT_SLICE = 284_792L;
    private static final long WORK_AT_DOUBLE_SLICE = 1_124_740L;

    /**
     * The build's work grows as the square of the ruleset, and the count tracks the whole of it.
     *
     * <p>Doubling the input should multiply the work by about four. The band is deliberately wide —
     * this is asserting a complexity class, not a constant — but it is narrow enough to separate
     * quadratic from linear or cubic, and, crucially, to fail if the count stops tracking the dominant
     * term: a counter that saw only the per-node winner would scale with node count, not with the
     * candidates x rules product, and would miss this band.
     */
    @Test
    void theWorkCountTracksTheQuadraticTerm() throws Exception {
        final var small = preprocessedSlice(SLICE);
        final var large = preprocessedSlice(SLICE * 2);
        assertThat(large)
                .as("the ratio below is only a doubling if the input actually doubled; every one of"
                        + " the first %s bundled rows is reversible today, but that is a property of"
                        + " the CSV rather than of this test", SLICE * 2)
                .hasSize(2 * small.size());

        final long smallWork = workToBuild(small);
        final long largeWork = workToBuild(large);

        // The magnitude, pinned. The ratio below is blind to a constant-factor error and to losing
        // the winner-site count; these are not. See WORK_AT_SLICE for the re-baselining rule.
        assertThat(smallWork)
                .as("work to build the first %s CSV rows (%s preprocessed rules, reversals included);"
                        + " see WORK_AT_SLICE before changing", SLICE, small.size())
                .isEqualTo(WORK_AT_SLICE);
        assertThat(largeWork)
                .as("work to build the first %s CSV rows (%s preprocessed rules, reversals included);"
                        + " see WORK_AT_SLICE before changing", SLICE * 2, large.size())
                .isEqualTo(WORK_AT_DOUBLE_SLICE);

        final double ratio = (double) largeWork / smallWork;
        assertThat(ratio)
                .as("doubling the ruleset must roughly quadruple the work (3.9493 as of 2026-09-07); a"
                        + " ratio near 2 means the count tracks something linear instead of the"
                        + " candidates x rules product. Kept alongside the pinned magnitudes because it"
                        + " survives a re-baselining: whoever updates those numbers still has to leave"
                        + " the growth quadratic")
                .isBetween(3.0, 5.0);
    }

    /**
     * The same ruleset costs the same work twice running — the property any future gate would rest on.
     *
     * <p>Weaker than it sounds, and worth naming: this is one JVM, one process, and the same
     * preprocessed list handed over twice. It does not establish stability across commits, JVM versions,
     * machines, or a re-preprocessed input, and {@code BundledRulesetTreeIdentityTest} records that these
     * quantities are coupled to hash iteration order with no enforcer pinning the JDK.
     */
    @Test
    void theSameRulesetCostsTheSameWorkTwice() throws Exception {
        final var rules = preprocessedSlice(SLICE);

        final long first = workToBuild(rules);
        final long second = workToBuild(rules);

        assertThat(second).isEqualTo(first);

        // Recorded, not asserted (#768). Locale.ROOT because the whole point is comparing this number
        // across machines and months, and a default-locale run prints 284.792 here and 284,792 in CI.
        System.out.printf(Locale.ROOT,
                "%n[#768] Tree.of build work, %,d-rule slice: %,d (candidates x rules), %.1f per rule.%n"
                        + "       Shipped-size and larger figures: make bench-jmh"
                        + " BENCH_TARGET=TreeBuildBenchmark%n",
                rules.size(), first, (double) first / rules.size());
    }


    /**
     * The counter equals the verdicts the build actually performed — checked against a count taken
     * somewhere other than the two {@code work.add} calls.
     *
     * <p>This is the assertion the pinned magnitudes and the ratio both lack. Neither can see work
     * the counter does not observe: review demonstrated it by adding a second, uncounted
     * {@code count(rules, bounds)} inside the scoring map, which doubled the verdicts performed and
     * left the reported number, the ratio, and the whole suite unchanged. A third scoring pass added
     * by some future change to #746 is exactly that shape.
     *
     * <p>The probe is the only candidate any rule carries, so every verdict in the build runs through
     * it and {@code scored} is the ground truth: the scoring pass walks both rules, and the winner's
     * list-building pass walks them again.
     */
    @Test
    void theCountEqualsTheVerdictsActuallyPerformed() throws Exception {
        final var probe = new CountingProbe();
        final var rules = List.of(
                DefaultRule.builder().withName("a").withProtocol("tcp").withDstPort(443).build(),
                DefaultRule.builder().withName("b").withProtocol("tcp").withDstPort(80).build());
        final var preprocessed = new ArrayList<PreprocessedRule>();
        for (final var rule : rules) {
            preprocessed.add(PreprocessedRule.of(rule));
        }
        for (final var rule : preprocessed) {
            rule.thresholds.clear();
        }
        preprocessed.get(0).thresholds.add(probe);

        final var work = new LongAdder();
        Tree.of(preprocessed, work);

        assertThat(probe.scored.get())
                .as("the probe must actually be reached, or the equality below is vacuous")
                .isPositive();
        assertThat(work.sum())
                .as("every verdict the build performed must be counted; a gap here means work is"
                        + " happening that the published figures do not include")
                .isEqualTo(probe.scored.get());
    }

    /** A lone candidate that counts the verdicts taken through it, independently of the accumulator. */
    private static final class CountingProbe extends Threshold<Integer> {

        private final AtomicInteger scored = new AtomicInteger();

        private CountingProbe() {
            super(bs -> bs.protocol, (bs, b) -> new Bounds(b, bs.srcPort, bs.dstPort, bs.srcAddr, bs.dstAddr));
        }

        @Override
        public Integer getThreshold() {
            // an unassigned protocol number, so this cannot collide with a real candidate
            return 253;
        }

        @Override
        public Order compare(final ClassificationRequest request) {
            return Order.NA;
        }

        @Override
        protected Match match(final PreprocessedRule rule, final Bounds bounds) {
            scored.incrementAndGet();
            return Match.NA;
        }
    }

    /** Work spent building one tree. The accumulator belongs to this build alone — no shared state. */
    private static long workToBuild(final List<PreprocessedRule> rules) throws InterruptedException {
        final var work = new LongAdder();
        Tree.of(rules, work);
        return work.sum();
    }

    /**
     * A slice of the shipped ruleset, preprocessed as {@code DefaultClassificationEngine.reload} does —
     * reversed twins included, because those double what the build works on. Real rules rather than
     * synthetic ones, so the thresholds cluster the way a real ruleset's do.
     */
    private static List<PreprocessedRule> preprocessedSlice(final int size) throws IOException {
        final List<Rule> bundled;
        try (var stream = TreeBuildWorkCounterTest.class.getResourceAsStream("/classification-rules.csv")) {
            bundled = new CsvImporter().parse(
                    Objects.requireNonNull(stream, "/classification-rules.csv is not on the test classpath"),
                    true);
        }

        assertThat(bundled)
                .as("#707: this class must never build the bundled ruleset — a slice of %s needs at"
                        + " least that many rows to take", size)
                .hasSizeGreaterThan(size);

        final var preprocessed = new ArrayList<PreprocessedRule>(size * 2);
        for (final var rule : bundled.subList(0, size)) {
            final var preprocessedRule = PreprocessedRule.of(rule);
            preprocessed.add(preprocessedRule);
            if (rule.canBeReversed()) {
                preprocessed.add(preprocessedRule.reverse());
            }
        }
        assertThat(preprocessed)
                .as("#707: a bundled build here costs 30-40 s under the coverage agent and moves no"
                        + " build counter, so the size is bounded rather than trusted. The bound is"
                        + " derived from the requested size so raising SLICE cannot trip a #707 message"
                        + " about a problem that is not happening")
                .hasSizeLessThanOrEqualTo(2 * size);
        return preprocessed;
    }
}
