/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.classification.internal.value;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #763 in all three value parsers at once. A rule column that is non-empty but names nothing
 * usable used to leave an empty result; {@code shrink} answers null for that; and
 * {@code Classifier.of}'s {@code addMatcher} then builds no matcher at all — so the condition was
 * dropped and the rule matched <em>every</em> value of that aspect.
 *
 * <p>Protocol was the reported case. Port and address are its siblings and were measured to have
 * the identical defect before this change: a rule with {@code dstPort=","} classified TCP/9999 and
 * one with {@code dstAddress=","} classified 1.2.3.4. They are covered here so the three cannot
 * drift apart, since nothing in the type system ties them together.</p>
 *
 * <p>These assert the exception <b>messages</b>, not just the throw. The messages are the only
 * thing that tells an operator which column is wrong, and an engine-level test cannot tell one
 * rejection reason from another — a review mutation that replaced both protocol messages with a
 * constant left every engine-level test green.</p>
 */
class ValueNamesNothingTest {

    @Test
    void protocolNamingNothingIsRefused() {
        assertThatThrownBy(() -> ProtocolValue.of(","))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("protocol")
                .hasMessageContaining("names no protocol at all")
                // the recovery instruction is the point of the message, not decoration
                .hasMessageContaining("Leave the column empty");
    }

    @Test
    void portNamingNothingIsRefused() {
        assertThatThrownBy(() -> PortValue.of(","))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("port")
                .hasMessageContaining("names no port at all")
                .hasMessageContaining("Leave the column empty");
    }

    @Test
    void addressNamingNothingIsRefused() {
        assertThatThrownBy(() -> IpValue.of(","))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("address")
                .hasMessageContaining("names no address at all")
                .hasMessageContaining("Leave the column empty");
    }

    /**
     * The unresolvable-keyword message names the offending keyword, and only it. Asserted with a
     * second, resolvable keyword present so the message cannot pass by echoing the whole input.
     */
    @Test
    void anUnresolvableProtocolKeywordIsNamedInTheMessage() {
        assertThatThrownBy(() -> ProtocolValue.of("tcp,tpc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'tpc'")
                .hasMessageContaining("cannot resolve")
                // the resolvable half must not be reported as the problem
                .hasMessageNotContaining("'tcp'");
    }

    /**
     * A decimal is not a keyword. {@code Protocols.getProtocol(String)} keys on the uppercased
     * keyword, so {@code protocol=6} does not resolve and the rule is refused. It was already
     * broken before #763 — the condition was dropped and the rule matched every protocol — so this
     * is a louder failure rather than a new restriction, but it is the most likely operator
     * mistake after a typo, and the message has to say which form is wanted.
     */
    @Test
    void aDecimalProtocolIsRefusedAndTheMessageSaysToUseAKeyword() {
        assertThatThrownBy(() -> ProtocolValue.of("6"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'6'")
                .hasMessageContaining("by keyword, not by number");
    }

    /** The control: ordinary values still parse, so the guards do not over-refuse. */
    @Test
    void ordinaryValuesStillParse() {
        assertThatCode(() -> ProtocolValue.of("tcp,udp")).doesNotThrowAnyException();
        assertThatCode(() -> PortValue.of("80,443-445")).doesNotThrowAnyException();
        assertThatCode(() -> IpValue.of("10.0.0.0/8")).doesNotThrowAnyException();
    }
}
