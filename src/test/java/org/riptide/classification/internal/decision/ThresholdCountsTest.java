/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.classification.internal.decision;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.riptide.classification.DefaultRule;
import org.riptide.classification.Rule;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the agreement between the two ways a threshold splits a rule set.
 *
 * <p>{@link Tree} scores every candidate threshold with {@link Threshold#count(java.util.Collection, Bounds)}
 * and then builds the collections with {@link Threshold#match(java.util.Collection, Bounds)} for the winner
 * alone. That is only sound while the two agree on every bucket, for every rule set and every bounds. The
 * two share their per-rule verdict, so they cannot drift by accident — but nothing in the type system says
 * so, and a counting path that miscounts one bucket would pick a different winner and build a different
 * tree. This is the test that names that property.
 */
public class ThresholdCountsTest {

    /**
     * A rule set chosen to reach every branch of every {@code match(PreprocessedRule, Bounds)}
     * implementation: all three threshold kinds, single values and ranges, ranges that straddle a
     * threshold (so lt, eq and gt are all true for one rule), several protocols on one rule, and rules
     * that leave an aspect unset (so the "na" bucket is non-empty).
     */
    private static List<PreprocessedRule> ruleSet() {
        // omnidirectional throughout, as every rule in the bundled ruleset is, so the reversed
        // half of the rule set is exercised too
        final List<Rule> rules = List.of(
                DefaultRule.builder().withName("single").withProtocol("tcp").withOmnidirectional(true)
                        .withSrcPort(1024).withDstPort(443)
                        .withSrcAddress("10.0.0.1").withDstAddress("192.168.1.1").build(),
                DefaultRule.builder().withName("ranges").withProtocol("tcp,udp,sctp").withOmnidirectional(true)
                        .withSrcPort("1000-2000").withDstPort("80,443,8080-8090")
                        .withSrcAddress("10.0.0.0/24").withDstAddress("192.168.1.1-192.168.3.254").build(),
                DefaultRule.builder().withName("straddles").withProtocol("udp").withOmnidirectional(true)
                        .withSrcPort("1-65535").withDstPort("1-65535")
                        .withSrcAddress("0.0.0.0-255.255.255.255").build(),
                // no protocol -> na for every protocol threshold
                DefaultRule.builder().withName("no-protocol").withOmnidirectional(true).withDstPort(53).build(),
                // no ports -> na for every port threshold
                DefaultRule.builder().withName("no-ports").withProtocol("tcp").withOmnidirectional(true)
                        .withSrcAddress("172.16.0.0/12").build(),
                // no addresses -> na for every address threshold
                DefaultRule.builder().withName("no-addresses").withProtocol("sctp").withOmnidirectional(true)
                        .withDstPort(2905).build(),
                // an aspect set on one side only
                DefaultRule.builder().withName("dst-only").withProtocol("tcp").withOmnidirectional(true)
                        .withDstPort(22).build(),
                DefaultRule.builder().withName("src-only").withProtocol("udp").withOmnidirectional(true)
                        .withSrcPort(68).build(),
                DefaultRule.builder().withName("v6").withProtocol("tcp").withOmnidirectional(true)
                        .withDstAddress("2001:db8::/32").withDstPort(993).build()
        );

        final var preprocessed = new ArrayList<PreprocessedRule>();
        for (final var rule : rules) {
            final var p = PreprocessedRule.of(rule);
            preprocessed.add(p);
            if (rule.canBeReversed()) {
                preprocessed.add(p.reverse());
            }
        }
        return preprocessed;
    }

    private static List<Threshold> candidates(final List<PreprocessedRule> rules) {
        final var candidates = new ArrayList<Threshold>();
        for (final var rule : rules) {
            for (final var threshold : rule.thresholds) {
                if (!candidates.contains(threshold)) {
                    candidates.add(threshold);
                }
            }
        }
        return candidates;
    }

    /**
     * The bounds a threshold is scored under during tree construction are never only {@link Bounds#ANY}:
     * the recursion restricts one axis at each level, and over several levels it restricts the same axis
     * more than once and several axes at once.
     *
     * <p>Restricting {@code ANY} once, which is all a single {@code lt}/{@code eq}/{@code gt} does,
     * therefore does not reproduce the shapes {@code Tree.of} passes down — it only ever produces
     * {@link Bound.Lt}, {@link Bound.Eq} and {@link Bound.Gt}. {@link Bound.In} needs a second
     * restriction on the same axis and is reached in every real build, so it is built here explicitly,
     * and a control below asserts one is actually present rather than trusting this comment.
     */
    private static List<Bounds> boundsFrom(final Threshold threshold, final List<Threshold> candidates) {
        final var bounds = new ArrayList<Bounds>();
        final var lt = threshold.lt(Bounds.ANY);
        final var gt = threshold.gt(Bounds.ANY);
        bounds.add(lt);
        bounds.add(threshold.eq(Bounds.ANY));
        bounds.add(gt);

        for (final var other : candidates) {
            // value equality, not identity: the candidate list is deduped by equals, so this skips
            // exactly the one entry that is this threshold
            if (other.equals(threshold)) {
                continue;
            }
            if (other.getClass() == threshold.getClass()) {
                // same axis: a second restriction is what turns Lt or Gt into In. Which direction is
                // legal depends on the two values' order, so both are offered to canRestrict.
                if (other.canRestrict(gt)) {
                    bounds.add(other.lt(gt));
                }
                if (other.canRestrict(lt)) {
                    bounds.add(other.gt(lt));
                }
            } else if (other.canRestrict(lt)) {
                // a different axis restricted at the same time, the other shape the recursion
                // produces and a one-axis-at-a-time fixture never does
                bounds.add(other.eq(lt));
            }
        }
        return bounds;
    }

    /** Whether any of the five axes of these bounds is an {@link Bound.In}. */
    private static boolean hasIn(final Bounds bounds) {
        return bounds.protocol instanceof Bound.In
                || bounds.srcPort instanceof Bound.In
                || bounds.dstPort instanceof Bound.In
                || bounds.srcAddr instanceof Bound.In
                || bounds.dstAddr instanceof Bound.In;
    }

    @Test
    @Timeout(60)
    void theCountsAgreeWithTheSizesOfTheCollectionsMatchBuilds() {
        final var rules = ruleSet();
        final var candidates = candidates(rules);

        // positive control: a degenerate rule set or candidate list would make every comparison
        // below trivially true, and this test would pass while proving nothing
        assertThat(rules).as("the rule set the buckets are counted over").hasSizeGreaterThan(10);
        assertThat(candidates).as("the candidate thresholds scored").hasSizeGreaterThan(30);

        final var allBounds = new ArrayList<Bounds>();
        allBounds.add(Bounds.ANY);
        for (final var candidate : candidates) {
            if (candidate.canRestrict(Bounds.ANY)) {
                allBounds.addAll(boundsFrom(candidate, candidates));
            }
        }

        // the control on the fixture: without an In, this test would be claiming coverage of a bound
        // shape it never built, which is what the previous version of it did
        assertThat(allBounds).as("the bounds the agreement is checked under")
                .anyMatch(ThresholdCountsTest::hasIn);
        assertThat(allBounds).as("and Eq, Lt and Gt, so In is not the only restricted shape")
                .anyMatch(b -> b.protocol instanceof Bound.Eq || b.dstPort instanceof Bound.Eq);

        var nonEmptyBuckets = 0;
        for (final var threshold : candidates) {
            // Bounds has no toString, so name the bounds by the index that reproduces them
            for (var i = 0; i < allBounds.size(); i++) {
                final var bounds = allBounds.get(i);
                final Threshold.Counts counts = threshold.count(rules, bounds);
                final Threshold.Matches matches = threshold.match(rules, bounds);

                assertThat(counts.lt()).as("lt of %s under bounds[%d]", threshold, i).isEqualTo(matches.lt.size());
                assertThat(counts.eq()).as("eq of %s under bounds[%d]", threshold, i).isEqualTo(matches.eq.size());
                assertThat(counts.gt()).as("gt of %s under bounds[%d]", threshold, i).isEqualTo(matches.gt.size());
                assertThat(counts.na()).as("na of %s under bounds[%d]", threshold, i).isEqualTo(matches.na.size());

                nonEmptyBuckets += counts.lt() > 0 ? 1 : 0;
                nonEmptyBuckets += counts.eq() > 0 ? 1 : 0;
                nonEmptyBuckets += counts.gt() > 0 ? 1 : 0;
                nonEmptyBuckets += counts.na() > 0 ? 1 : 0;
            }
        }

        // the second positive control: agreement on four zeroes is not agreement worth having
        assertThat(nonEmptyBuckets).as("bucket comparisons that had something in them").isGreaterThan(1000);
    }

    /**
     * Every bucket must actually be reached, or the comparison above could agree on a bucket that is
     * always empty. Each of the four is claimed here against a rule set that puts something in it.
     */
    @Test
    @Timeout(60)
    void everyBucketIsReachedByTheRuleSetTheAgreementIsCheckedOver() {
        final var rules = ruleSet();

        // "straddles" covers 1-65535, so it lands in lt, eq and gt at once; "no-ports" has no source
        // port at all, so it lands in na
        final var srcPort = new Threshold.SrcPort(1024);
        final var counts = srcPort.count(rules, Bounds.ANY);

        assertThat(counts.lt()).as("lt").isPositive();
        assertThat(counts.eq()).as("eq").isPositive();
        assertThat(counts.gt()).as("gt").isPositive();
        assertThat(counts.na()).as("na").isPositive();

        final var matches = srcPort.match(rules, Bounds.ANY);
        assertThat(counts.lt()).isEqualTo(matches.lt.size());
        assertThat(counts.eq()).isEqualTo(matches.eq.size());
        assertThat(counts.gt()).isEqualTo(matches.gt.size());
        assertThat(counts.na()).isEqualTo(matches.na.size());
    }
}
