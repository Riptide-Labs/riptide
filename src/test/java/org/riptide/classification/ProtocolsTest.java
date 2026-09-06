/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.classification;

import org.junit.jupiter.api.Test;
import org.riptide.classification.internal.DefaultClassificationEngine;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two things, kept in one class because they are the same table read from both ends.
 *
 * <p>The table is a copy of IANA's "Assigned Internet Protocol Numbers" registry, so the first
 * thing worth testing is that it still <b>agrees with the registry</b> — the assignments it carries,
 * the gap it leaves, and the two divergences it keeps on purpose (#758).</p>
 *
 * <p>The second is what happens to a rule naming a keyword the table does <b>not</b> carry (#763).
 * That is a property of {@code ProtocolValue.of} rather than of the table, and the messages it
 * throws are pinned in {@code ValueNamesNothingTest}; the rows here go through the real engine
 * because what matters is that the rule is rejected and the rest of the ruleset keeps serving.</p>
 */
class ProtocolsTest {

    /**
     * #758: the table jumped from 142 straight to 253, so every number in between answered null.
     * IANA has assigned five of them, not the three the issue named — 146 and 147 were found by
     * reconciling the whole range instead of adding the numbers the issue happened to list.
     *
     * <p>A flow carrying one of these was classified as though it had no protocol at all: since
     * #757 that is a non-match rather than a crash, so the cost is that a rule naming the protocol
     * cannot be written and the traffic falls through to whatever protocol-less rules exist.</p>
     */
    @Test
    void theAssignmentsBetween143And147AreMapped() {
        final Map<Integer, String> assigned = new HashMap<>();
        assigned.put(143, "Ethernet");
        assigned.put(144, "AGGFRAG");
        assigned.put(145, "NSH");
        assigned.put(146, "Homa");
        assigned.put(147, "BIT-EMU");

        assertThat(assigned).allSatisfy((decimal, keyword) -> {
            assertThat(Protocols.getProtocol(decimal))
                    .as("IANA assigns %s to %s, so a flow carrying it must resolve", decimal, keyword)
                    .isNotNull()
                    .extracting(Protocol::getKeyword)
                    .isEqualTo(keyword);

            // and the rule side: an operator must be able to name it
            assertThat(Protocols.getProtocol(keyword))
                    .as("a rule naming %s must resolve to %s", keyword, decimal)
                    .isNotNull()
                    .extracting(Protocol::getDecimal)
                    .isEqualTo(decimal);
        });
    }

    /**
     * 148-252 are Unassigned upstream, so the table agrees with the registry by having nothing.
     *
     * <p><b>If this fails, the fix is usually to move the bound, not to delete the row.</b> This
     * pins nothing the #758 change introduced — it passed before it too — and its realistic failure
     * mode is a <em>correct</em> future addition once IANA assigns 148. Kept because "the gap below
     * 253 is the registry's, not an omission" is the whole conclusion of that reconciliation, and
     * without an assertion the next reader has to re-fetch the registry to learn it.</p>
     */
    @Test
    void theRangeIanaLeavesUnassignedStaysUnmapped() {
        for (int decimal = 148; decimal <= 252; decimal++) {
            assertThat(Protocols.getProtocol(decimal))
                    .as("%s is Unassigned at IANA and must not be invented here", decimal)
                    .isNull();
        }
    }

    /**
     * Protocol 84 is listed twice — {@code TTP} and {@code IPTM}. IANA carries only IPTM and
     * records that the value was assigned to TTP in error, so the extra row is a historical
     * artefact rather than a second protocol.
     *
     * <p>It is kept for compatibility: the keyword is what an operator rule names, so dropping it
     * breaks every rule naming {@code ttp}. Since #763 that break is loud — {@code ProtocolValue.of}
     * refuses a rule whose keyword does not resolve and the reloader names it in a WARN — where it
     * used to be silent widening, the rule matching every protocol. See
     * {@link #aRuleNamingAnUnresolvableProtocolIsRejected()}. Both rows carry decimal 84 and
     * {@code ProtocolMatcher} compares decimals, so classification is unaffected either way.</p>
     *
     * <p>This pins the anomaly at exactly one so it cannot grow unnoticed, and so the next reader
     * finds the reason instead of "fixing" it.</p>
     */
    @Test
    void eightyFourIsTheOnlyDecimalListedTwice() {
        final Set<Integer> seen = new HashSet<>();
        final Set<Integer> duplicated = new HashSet<>();
        for (final Protocol protocol : Protocols.getProtocols()) {
            if (!seen.add(protocol.getDecimal())) {
                duplicated.add(protocol.getDecimal());
            }
        }

        assertThat(duplicated)
                .as("a duplicate decimal makes getProtocols() double-count and the decimal map "
                        + "order-dependent; 84 is the one documented exception")
                .containsExactly(84);

        // the current assignment is what a flow carrying 84 resolves to, by insertion order
        assertThat(Protocols.getProtocol(84).getKeyword()).isEqualTo("IPTM");
        // and the historical keyword still resolves, so an existing rule naming it keeps working
        assertThat(Protocols.getProtocol("ttp")).isNotNull()
                .extracting(Protocol::getDecimal).isEqualTo(84);
    }

    /**
     * IANA renamed 55 from {@code MOBILE} to {@code Min-IPv4}. The old keyword is kept for the
     * same reason as {@code TTP} above: renaming it would break every operator rule naming
     * {@code mobile} — since #763 by refusing them, which is at least visible. Pinned so the
     * divergence is a decision on the record rather than something a later reconciliation quietly
     * "corrects".
     */
    @Test
    void fiftyFiveKeepsTheKeywordOperatorRulesAlreadyUse() {
        assertThat(Protocols.getProtocol(55)).isNotNull()
                .extracting(Protocol::getKeyword).isEqualTo("MOBILE");
        assertThat(Protocols.getProtocol("mobile")).isNotNull()
                .extracting(Protocol::getDecimal).isEqualTo(55);
    }

    /**
     * #763: a rule naming a protocol keyword the table cannot resolve is rejected, not honoured in
     * part and not silently widened.
     *
     * <p>It used to be the third instance of #759's defect. {@code ProtocolValue.of} dropped the
     * unresolvable keyword, leaving an empty protocol set; {@code shrink} answered null for that;
     * and {@code Classifier.of}'s {@code addMatcher} then built no {@code ProtocolMatcher} at all,
     * so the condition vanished and the rule matched <em>every</em> protocol. An operator who
     * typed {@code tpc} got a rule claiming all traffic on its ports, with nothing logged.</p>
     *
     * <p>{@code Min-IPv4} is the unknown keyword on purpose: it is IANA's current name for 55, so
     * this row also shows what "correcting" that keyword would now cost — a rejection, which is
     * visible, rather than the silent widening it used to cost.</p>
     */
    @Test
    void aRuleNamingAnUnresolvableProtocolIsRejected() throws Exception {
        final var engine = new DefaultClassificationEngine(() -> List.of(
                DefaultRule.builder().withName("http").withPosition(1).withDstPort(80).build(),
                DefaultRule.builder().withName("ghost").withPosition(2).withProtocol("Min-IPv4").build()));

        assertThat(engine.getInvalidRules())
                .as("the unresolvable rule is rejected, and only that one")
                .extracting(Rule::getName)
                .containsExactly("ghost");

        // it classifies nothing rather than claiming protocols it never named
        assertThat(engine.classify(ClassificationRequest.builder()
                .withProtocol(ProtocolType.UDP).withDstPort(443).build()))
                .as("the rejected rule must not claim UDP/443")
                .isNull();

        // and the rest of the ruleset keeps serving
        assertThat(engine.classify(ClassificationRequest.builder()
                .withProtocol(ProtocolType.TCP).withDstPort(80).build()))
                .isEqualTo("http");
    }

    /**
     * The list case, decided deliberately: one unresolvable keyword refuses the whole rule, rather
     * than the rule quietly matching the subset that did resolve. A rule reading {@code tcp,tpc}
     * cannot be honoured as written, and honouring half of it is the same "quietly does something
     * other than what it says" this area keeps paying for. The rejection is named in the reloader's
     * WARN, so the typo is visible instead of costing the operator UDP traffic they never notice.
     */
    @Test
    void oneUnresolvableKeywordRefusesTheWholeRule() throws Exception {
        final var engine = new DefaultClassificationEngine(() -> List.of(
                DefaultRule.builder().withName("typo").withPosition(1).withProtocol("tcp,tpc")
                        .withDstPort(80).build()));

        assertThat(engine.getInvalidRules()).extracting(Rule::getName).containsExactly("typo");

        assertThat(engine.classify(ClassificationRequest.builder()
                .withProtocol(ProtocolType.TCP).withDstPort(80).build()))
                .as("not honoured for the half that resolved either")
                .isNull();
    }

    /**
     * A protocol column that is non-empty but names nothing. {@code StringValue.splitBy} trims and
     * drops empty segments, so there is no keyword to report unresolvable — yet the result is the
     * same empty set that used to drop the condition. Pinned because the keyword-level guard alone
     * does not cover it, and a reader would reasonably assume it did.
     */
    @Test
    void aProtocolNamingNothingIsRejected() throws Exception {
        final var engine = new DefaultClassificationEngine(() -> List.of(
                DefaultRule.builder().withName("comma").withPosition(1).withProtocol(",")
                        .withDstPort(80).build()));

        assertThat(engine.getInvalidRules()).extracting(Rule::getName).containsExactly("comma");
        assertThat(engine.classify(ClassificationRequest.builder()
                .withProtocol(ProtocolType.TCP).withDstPort(80).build())).isNull();
    }

    /**
     * The control, so the guards are not shown only to over-refuse: a fully resolvable list is
     * accepted and matches. The four keywords are the ones the bundled ruleset uses, but this row
     * hard-codes them rather than reading the CSV — {@code BundledRulesetTreeIdentityTest} and
     * {@code verifyBundledRulesetKeepsBroadRulesLast} are what actually pin the shipped file.
     */
    @Test
    void aFullyResolvableProtocolListIsAccepted() throws Exception {
        final var engine = new DefaultClassificationEngine(() -> List.of(
                DefaultRule.builder().withName("multi").withPosition(1)
                        .withProtocol("tcp,udp,sctp,dccp").withDstPort(80).build()));

        assertThat(engine.getInvalidRules()).isEmpty();
        assertThat(engine.classify(ClassificationRequest.builder()
                .withProtocol(ProtocolType.UDP).withDstPort(80).build())).isEqualTo("multi");
    }
}
