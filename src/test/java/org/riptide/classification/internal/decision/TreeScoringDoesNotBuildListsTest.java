/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.classification.internal.decision;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.riptide.classification.ClassificationRequest;
import org.riptide.classification.DefaultRule;
import org.riptide.classification.Rule;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the thing #746 changed: scoring a candidate threshold must not build its collections.
 *
 * <p>Every other test of this change is satisfied by code that does not have it. Replace
 * {@code Threshold.count}'s body with {@code var m = match(ruleSet, bounds); return new
 * Counts(m.lt.size(), ...)} and the suite stays green from end to end — {@code ThresholdCountsTest}
 * becomes a tautology comparing {@code match}'s sizes against {@code match}'s sizes, the fingerprint and
 * the answer digest are untouched because the tree really is identical, and the {@code @Timeout}s are
 * ceilings rather than measurements. The whole gain of the change can go back to zero with nothing red.
 *
 * <p>A wall-clock assertion is not the answer. Every surefire JVM here carries the JaCoCo agent, which
 * inflates this build 20-40x and varies with machine load; the repo already reasoned itself out of
 * timing assertions on exactly this code. So the property is pinned by shape instead: a candidate
 * threshold that is scored and loses must never have {@code match(Collection, Bounds)} called on it.
 * That is deterministic, costs milliseconds, and is independent of the agent.
 */
public class TreeScoringDoesNotBuildListsTest {

    /**
     * A candidate that records how it was asked.
     *
     * <p>It deliberately does <em>not</em> override {@code count}: the point is to run the real counting
     * loop, so that a {@code count} reimplemented in terms of {@code match} calls the list-building
     * method below and is caught. Its per-rule verdict is NA for every rule, which puts every rule in
     * the "na" bucket, so its largest bucket equals the rule-set size and {@code Tree.of}'s
     * {@code maximumSize} filter always discards it. It is therefore always scored and never the
     * winner — which is exactly the position the change is about.
     */
    private static final class ScoringProbe extends Threshold<Integer> {

        private final AtomicInteger scored = new AtomicInteger();
        private final AtomicInteger listsBuilt = new AtomicInteger();

        private ScoringProbe() {
            super(bs -> bs.protocol, (bs, b) -> new Bounds(b, bs.srcPort, bs.dstPort, bs.srcAddr, bs.dstAddr));
        }

        @Override
        public Integer getThreshold() {
            // an unassigned protocol number, so this candidate cannot collide with a real one
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

        @Override
        public Matches match(final Collection<PreprocessedRule> ruleSet, final Bounds bounds) {
            listsBuilt.incrementAndGet();
            return super.match(ruleSet, bounds);
        }
    }

    private static List<PreprocessedRule> rulesCarrying(final Threshold probe) {
        final List<Rule> rules = List.of(
                DefaultRule.builder().withName("a").withProtocol("tcp").withOmnidirectional(true)
                        .withDstPort(443).build(),
                DefaultRule.builder().withName("b").withProtocol("udp").withOmnidirectional(true)
                        .withDstPort(53).build(),
                DefaultRule.builder().withName("c").withProtocol("tcp,udp").withOmnidirectional(true)
                        .withSrcPort("1000-2000").withDstPort(80).build(),
                DefaultRule.builder().withName("d").withProtocol("tcp").withOmnidirectional(true)
                        .withDstPort("8080-8090").build(),
                DefaultRule.builder().withName("e").withProtocol("sctp").withOmnidirectional(true)
                        .withDstPort(2905).build(),
                DefaultRule.builder().withName("f").withProtocol("tcp").withOmnidirectional(true)
                        .withSrcAddress("10.0.0.0/24").withDstPort(22).build()
        );

        final var preprocessed = new ArrayList<PreprocessedRule>();
        for (final var rule : rules) {
            final var p = PreprocessedRule.of(rule);
            preprocessed.add(p);
            if (rule.canBeReversed()) {
                preprocessed.add(p.reverse());
            }
        }

        // The candidate set Tree.of scores is exactly the union of the rules' own threshold sets, and
        // that set is the only way in. PreprocessedRule builds it with Collectors.toSet(), which is
        // mutable in practice but not by contract, so the insertion is checked rather than assumed.
        preprocessed.get(0).thresholds.add(probe);
        assertThat(preprocessed.get(0).thresholds)
                .as("the probe must actually reach the candidate set, or this test proves nothing")
                .contains(probe);
        return preprocessed;
    }

    @Test
    @Timeout(60)
    void scoringACandidateNeverBuildsItsCollections() throws InterruptedException {
        final var probe = new ScoringProbe();
        final var rules = rulesCarrying(probe);

        final var tree = Tree.of(rules);
        assertThat(tree).as("the build must actually have happened").isNotNull();

        // positive control: without it, a probe filtered out before scoring - by canRestrict, or by
        // never reaching the candidate set at all - would satisfy the assertion below trivially
        assertThat(probe.scored.get())
                .as("the probe must have been scored, or the pin below is vacuous")
                .isPositive();

        assertThat(probe.listsBuilt.get())
                .as("Tree.of scored a candidate by building its collections instead of counting them")
                .isZero();
    }

    /**
     * The winner is the one candidate whose collections {@code Tree.of} is supposed to build, and it
     * still must, because the recursion consumes them. A change that stopped calling {@code match}
     * altogether would pass the row above.
     */
    @Test
    @Timeout(60)
    void theWinningCandidateStillHasItsCollectionsBuilt() throws InterruptedException {
        final var winner = new AlwaysWins();
        final List<Rule> rules = List.of(
                DefaultRule.builder().withName("a").withProtocol("tcp").withOmnidirectional(true)
                        .withDstPort(443).build(),
                DefaultRule.builder().withName("b").withProtocol("tcp").withOmnidirectional(true)
                        .withDstPort(80).build()
        );
        final var preprocessed = new ArrayList<PreprocessedRule>();
        for (final var rule : rules) {
            preprocessed.add(PreprocessedRule.of(rule));
        }
        preprocessed.get(0).thresholds.clear();
        preprocessed.get(1).thresholds.clear();
        preprocessed.get(0).thresholds.add(winner);
        assertThat(preprocessed.get(0).thresholds).contains(winner);

        Tree.of(preprocessed);

        assertThat(winner.listsBuilt.get())
                .as("the winning threshold's collections are what the recursion descends into")
                .isEqualTo(1);
    }

    /**
     * The mirror of {@link ScoringProbe}: a lone candidate that splits the rules unevenly enough to
     * pass the {@code maximumSize} filter, so it is the only thing that can win.
     */
    private static final class AlwaysWins extends Threshold<Integer> {

        private final AtomicInteger listsBuilt = new AtomicInteger();

        private AlwaysWins() {
            super(bs -> bs.protocol, (bs, b) -> new Bounds(b, bs.srcPort, bs.dstPort, bs.srcAddr, bs.dstAddr));
        }

        @Override
        public Integer getThreshold() {
            return 254;
        }

        @Override
        public Order compare(final ClassificationRequest request) {
            return Order.NA;
        }

        @Override
        protected Match match(final PreprocessedRule rule, final Bounds bounds) {
            // one rule below the threshold and one above, so no bucket holds them all
            return "a".equals(rule.rule.getName())
                    ? new Match(true, false, false, false)
                    : new Match(false, false, true, false);
        }

        @Override
        public Matches match(final Collection<PreprocessedRule> ruleSet, final Bounds bounds) {
            listsBuilt.incrementAndGet();
            return super.match(ruleSet, bounds);
        }
    }
}
