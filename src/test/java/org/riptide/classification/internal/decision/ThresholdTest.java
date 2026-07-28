/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.classification.internal.decision;

import org.junit.jupiter.api.Test;
import org.riptide.classification.DefaultRule;
import org.riptide.classification.IpAddr;

import static org.assertj.core.api.Assertions.assertThat;

public class ThresholdTest {

    /**
     * The getClass()-based equals in {@link Threshold.Port} and {@link Threshold.Address} is
     * load-bearing: src- and dst-side thresholds with equal values must coexist in the
     * candidate-threshold set or the decision tree loses one axis's split candidates. This pins
     * the invariant against a future instanceof "modernization" (see the suppressions in
     * {@link Threshold}).
     */
    @Test
    void verifySrcAndDstThresholdsOfEqualValueStayDistinct() {
        assertThat(new Threshold.SrcPort(443)).isNotEqualTo(new Threshold.DstPort(443));
        assertThat(new Threshold.SrcAddress(IpAddr.of("10.0.0.1")))
                .isNotEqualTo(new Threshold.DstAddress(IpAddr.of("10.0.0.1")));

        // and end to end: a rule with equal src/dst values keeps a candidate per side
        final var rule = DefaultRule.builder().withName("r").withProtocol("tcp")
                .withSrcPort(443).withDstPort(443)
                .withSrcAddress("10.0.0.1").withDstAddress("10.0.0.1")
                .build();
        final var thresholds = PreprocessedRule.of(rule).thresholds;
        assertThat(thresholds).filteredOn(t -> t instanceof Threshold.SrcPort).hasSize(1);
        assertThat(thresholds).filteredOn(t -> t instanceof Threshold.DstPort).hasSize(1);
        assertThat(thresholds).filteredOn(t -> t instanceof Threshold.SrcAddress).isNotEmpty();
        assertThat(thresholds).filteredOn(t -> t instanceof Threshold.DstAddress).isNotEmpty();
    }
}
