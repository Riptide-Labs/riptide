/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.classification.internal.matcher;

import org.junit.jupiter.api.Test;
import org.riptide.classification.ClassificationRequest;
import org.riptide.classification.Protocols;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A leaf matcher whose aspect is absent from the request reports "no match" instead of throwing.
 *
 * <p>These are the semantics the tree above the leaves already implements:
 * {@code Threshold.Protocol.compare}, {@code Threshold.Port.compare} and
 * {@code Threshold.Address.compare} each test the request field for null and answer {@code Order.NA},
 * so "this flow has no protocol" is a state the tree routes on. Before #750 only the leaves disagreed,
 * and they disagreed by throwing.
 *
 * <p>Reachability differs per matcher and the rows say so. Only the protocol one is reachable with the
 * ruleset riptide ships: {@code ClassificationEnricher} builds every request through
 * {@code Protocols.getProtocol(Integer)}, which answers null for any protocol number riptide does not
 * map, and the bundled tree routes such a request into a leaf holding a {@code ProtocolMatcher} — see
 * {@code ClassificationEnricherTest}. The port and address siblings are latent <em>there</em>: a
 * portless request is routed away by the port thresholds before any {@code PortMatcher} runs, and the
 * bundled ruleset names no addresses at all. Latent is not unreachable, and a ruleset is
 * operator-supplied data: all three are reachable through the real engine on a ruleset whose condition
 * never becomes a threshold, which is what the three {@code aRuleNaming...} rows in
 * {@code DefaultClassificationEngineTest} show.
 *
 * <p>Each matcher is exercised through its public subclass, which is how {@code Classifier.of} builds
 * them, so the extractor under test is the real one and not a lambda this test invented.
 */
class AbsentAspectIsNoMatchTest {

    /** No protocol at all: what an unmapped protocol number becomes by the time a matcher sees it. */
    @Test
    void aProtocolMatcherDoesNotMatchARequestWithoutAProtocol() {
        final var request = ClassificationRequest.builder()
                .withSrcPort(54321)
                .withDstPort(80)
                .build();

        assertThat(new ProtocolMatcher("tcp").matches(request)).isFalse();
    }

    /** The control: naming a protocol must still match the flows that carry it, and only those. */
    @Test
    void aProtocolMatcherStillDecidesRequestsThatCarryAProtocol() {
        final var tcp = ClassificationRequest.builder()
                .withProtocol(Protocols.getProtocol("tcp"))
                .withDstPort(80)
                .build();
        final var udp = ClassificationRequest.builder()
                .withProtocol(Protocols.getProtocol("udp"))
                .withDstPort(80)
                .build();

        assertThat(new ProtocolMatcher("tcp").matches(tcp)).isTrue();
        assertThat(new ProtocolMatcher("tcp").matches(udp)).isFalse();
    }

    /**
     * A null {@code Integer} port used to auto-unbox into {@code PortValue.matches(int)}. Each
     * direction is asserted with the other port populated, so a matcher reading the wrong field would
     * not be able to pass by accident.
     */
    @Test
    void aPortMatcherDoesNotMatchARequestWithoutThatPort() {
        final var noSrcPort = ClassificationRequest.builder().withDstPort(80).build();
        final var noDstPort = ClassificationRequest.builder().withSrcPort(80).build();

        assertThat(new SrcPortMatcher("80").matches(noSrcPort)).isFalse();
        assertThat(new DstPortMatcher("80").matches(noDstPort)).isFalse();
    }

    /** The control: naming a port must still match the flows that carry it, and only those. */
    @Test
    void aPortMatcherStillDecidesRequestsThatCarryThatPort() {
        final var request = ClassificationRequest.builder()
                .withSrcPort(54321)
                .withDstPort(80)
                .build();

        assertThat(new DstPortMatcher("80").matches(request)).isTrue();
        assertThat(new DstPortMatcher("443").matches(request)).isFalse();
        assertThat(new SrcPortMatcher("54321").matches(request)).isTrue();
        assertThat(new SrcPortMatcher("80").matches(request)).isFalse();
    }

    /**
     * The extractor yields an {@code IpAddr}, so this path takes {@code IpValue.isInRange(IpAddr)} and
     * never the {@code String} overload with its {@code Objects.requireNonNull}. The null used to reach
     * {@code IpRange.contains}, where {@code begin.compareTo(null)} unboxes the other side's value.
     */
    @Test
    void anAddressMatcherDoesNotMatchARequestWithoutThatAddress() {
        final var noSrcAddress = ClassificationRequest.builder().withDstAddress("10.20.20.10").build();
        final var noDstAddress = ClassificationRequest.builder().withSrcAddress("10.10.10.10").build();

        assertThat(new SrcAddressMatcher("10.0.0.0/8").matches(noSrcAddress)).isFalse();
        assertThat(new DstAddressMatcher("10.0.0.0/8").matches(noDstAddress)).isFalse();
    }

    /** The control: naming an address must still match the flows that carry it, and only those. */
    @Test
    void anAddressMatcherStillDecidesRequestsThatCarryThatAddress() {
        final var request = ClassificationRequest.builder()
                .withSrcAddress("10.10.10.10")
                .withDstAddress("192.168.1.1")
                .build();

        assertThat(new SrcAddressMatcher("10.0.0.0/8").matches(request)).isTrue();
        assertThat(new SrcAddressMatcher("192.168.0.0/16").matches(request)).isFalse();
        assertThat(new DstAddressMatcher("192.168.0.0/16").matches(request)).isTrue();
        assertThat(new DstAddressMatcher("10.0.0.0/8").matches(request)).isFalse();
    }
}
