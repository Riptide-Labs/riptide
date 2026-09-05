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
 * The table is a copy of IANA's "Assigned Internet Protocol Numbers" registry, so the thing worth
 * testing is that it still agrees with the registry rather than that it parses.
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
     * <p>It is kept deliberately, because dropping a keyword makes rules naming it match
     * <em>more</em>, not less. {@code ProtocolValue.of} filters an unresolvable keyword out and
     * leaves an empty protocol set; {@code ProtocolValue.shrink} answers null for that; and
     * {@code Classifier.of}'s {@code addMatcher} then builds no {@code ProtocolMatcher} at all, so
     * the protocol condition is dropped and the rule matches every protocol. That is the same
     * silent widening #759 was about, and it is measured rather than reasoned — see
     * {@link #aRuleNamingAnUnknownProtocolMatchesEverything()}. Both rows carry decimal 84 and
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
     * same reason as {@code TTP} above: renaming it would make any rule naming {@code mobile}
     * match every protocol. Pinned so the divergence is a decision on the record rather than
     * something a later reconciliation quietly "corrects".
     */
    @Test
    void fiftyFiveKeepsTheKeywordOperatorRulesAlreadyUse() {
        assertThat(Protocols.getProtocol(55)).isNotNull()
                .extracting(Protocol::getKeyword).isEqualTo("MOBILE");
        assertThat(Protocols.getProtocol("mobile")).isNotNull()
                .extracting(Protocol::getDecimal).isEqualTo(55);
    }

    /**
     * The cost of dropping a keyword, measured rather than argued — this is the evidence the two
     * retention decisions above rest on, and it pins behaviour that is easy to get backwards.
     *
     * <p>A rule naming only a keyword the table does not carry is <b>not</b> rejected and does
     * <b>not</b> match nothing: the condition is dropped, so it matches every protocol. That is
     * #759's defect in the protocol aspect, and it is a live bug in its own right — filed as #763
     * rather than fixed here, because refusing such a rule is a behaviour change for every
     * operator with a typo in a protocol column, not a data reconciliation.</p>
     *
     * <p>{@code Min-IPv4} is used as the unknown keyword on purpose: it is IANA's current name for
     * 55, so this row also shows what "correcting" that keyword would cost.</p>
     */
    @Test
    void aRuleNamingAnUnknownProtocolMatchesEverything() throws Exception {
        final var engine = new DefaultClassificationEngine(() -> List.of(
                DefaultRule.builder().withName("ghost").withPosition(1).withProtocol("Min-IPv4").build()));

        assertThat(engine.getInvalidRules())
                .as("the rule is accepted — nothing rejects an unresolvable protocol keyword")
                .isEmpty();

        assertThat(engine.classify(ClassificationRequest.builder()
                .withProtocol(ProtocolType.TCP).withDstPort(80).build()))
                .as("the protocol condition is dropped, so the rule claims TCP it never named")
                .isEqualTo("ghost");
        assertThat(engine.classify(ClassificationRequest.builder()
                .withProtocol(ProtocolType.UDP).withDstPort(443).build()))
                .as("and UDP too — this is silent widening, not a non-match")
                .isEqualTo("ghost");
    }
}
