/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.inventory;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The strict address parser and its diagnosis ladder (#538): a typo must fail rather than
 * quietly mean a different address, and the failure must name the rule that fired plus the
 * exact string to write instead.
 */
class StrictAddressDiagnosisTest {

    /**
     * The trap this change closes, asserted with its own evidence: the non-contiguous mask
     * zeroes bits the operator wrote, and under the old parameters the result was accepted
     * as host 10.0.0.0 — which, being a single host, also sailed past the v1/v2c width
     * rule. The message names the collapsed address, because that is what makes the
     * refusal convincing.
     */
    @Test
    void aNonContiguousNetmaskIsRefusedNamingTheAddressItWouldHaveMeant() {
        assertThatThrownBy(() -> StrictAddresses.parse("10.90.0.0/255.0.255.0", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-contiguous netmask")
                .hasMessageContaining("10.0.0.0")
                .hasMessageContaining("no CIDR equivalent");
    }

    /** A contiguous netmask has an exact CIDR spelling, and the message hands it over. */
    @Test
    void aContiguousNetmaskIsRefusedNamingTheCidrForm() {
        assertThatThrownBy(() -> StrictAddresses.parse("10.90.0.0/255.255.0.0", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("uses a netmask")
                .hasMessageContaining("10.90.0.0/16");
        assertThatThrownBy(() -> StrictAddresses.parse("10.0.0.7/255.255.255.255", false))
                .hasMessageContaining("10.0.0.7/32");
    }

    /** The IPv6 case that drove the original complaint: the fix is in the message. */
    @Test
    void leadingZerosAreRefusedNamingTheCanonicalForm() {
        assertThatThrownBy(() -> StrictAddresses.parse("2001:0db8::1", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("leading zeros")
                .hasMessageContaining("2001:db8::1");
        assertThatThrownBy(() -> StrictAddresses.parse("010.0.0.7", false))
                .hasMessageContaining("leading zeros")
                .hasMessageContaining("10.0.0.7");
    }

    @Test
    void inetAtonShorthandIsRefusedNamingAllFourOctets() {
        assertThatThrownBy(() -> StrictAddresses.parse("10.0.1", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inet_aton")
                .hasMessageContaining("10.0.0.1");
    }

    /** The ladder's fallback: nonsense still gets the original generic message. */
    @Test
    void nonsenseFallsThroughToTheGenericMessage() {
        assertThatThrownBy(() -> StrictAddresses.parse("not-an-address", false))
                .hasMessage("is not a host address or CIDR prefix.");
        assertThatThrownBy(() -> StrictAddresses.parse("not-an-address", true))
                .hasMessage("is not a single host address.");
    }

    /** Wrong shape after a clean parse names the shape rule and the fix. */
    @Test
    void wrongShapeNamesTheRuleAndBothFixes() {
        // host bits set with a prefix: the two legitimate readings, both offered
        assertThatThrownBy(() -> StrictAddresses.parse("10.0.1.5/24", false))
                .hasMessageContaining("host bits set")
                .hasMessageContaining("10.0.1.0/24")
                .hasMessageContaining("10.0.1.5");
        // a wildcard that covers exactly one block gets its CIDR form
        assertThatThrownBy(() -> StrictAddresses.parse("10.0.0.*", false))
                .hasMessageContaining("wildcard")
                .hasMessageContaining("10.0.0.0/24");
        // a range where only a host is allowed keeps the original message
        assertThatThrownBy(() -> StrictAddresses.parse("10.0.0.0/24", true))
                .hasMessage("is not a single host address.");
    }

    /**
     * The first version called a contiguous mask with host bits "non-contiguous" — every
     * clause of that message was false (the mask was contiguous, nothing collapsed, CIDR
     * fixes existed). Contiguity is the mask's property, not the result shape's.
     */
    @Test
    void aContiguousMaskKeepingHostBitsIsDiagnosedTruthfully() {
        assertThatThrownBy(() -> StrictAddresses.parse("10.90.0.5/255.255.0.0", false))
                .hasMessageContaining("keeps host bits")
                .hasMessageContaining("10.90.0.0/16")
                .hasMessageContaining("10.90.0.5")
                .hasMessageNotContaining("non-contiguous");
    }

    /** Spellings mixing several rejected forms get one diagnosis, not the generic shrug. */
    @Test
    void combinedViolationsStillGetADiagnosis() {
        assertThatThrownBy(() -> StrictAddresses.parse("010.0.0.0/255.0.0.0", false))
                .hasMessageContaining("combines several rejected spellings")
                .hasMessageContaining("10.0.0.0/8");
    }

    /**
     * Every suggestion the ladder emits must round-trip through this parser: an error that
     * hands the operator a string the same parser then rejects is worse than a generic
     * one, and the first version did exactly that ("write '10.0.0.5/24'" after fixing
     * leading zeros on a host-bits spelling).
     */
    @Test
    void everySuggestedFixRoundTrips() {
        final String[] rejected = {
                "10.90.0.0/255.0.255.0", "10.90.0.0/255.255.0.0", "10.0.0.7/255.255.255.255",
                "10.90.0.5/255.255.0.0", "2001:0db8::1", "010.0.0.7", "010.0.0.5/24",
                "10.0.1", "10.0.1/24", "10.0.0.*", "10.0.1.5/24", "010.0.0.0/255.0.0.0",
                "1::1/ffff::"};
        final java.util.regex.Pattern quoted = java.util.regex.Pattern.compile("'([^']+)'");
        for (final String value : rejected) {
            String message;
            try {
                StrictAddresses.parse(value, false);
                continue; // accepted spellings have nothing to round-trip
            } catch (final IllegalArgumentException e) {
                message = e.getMessage();
            }
            final var matcher = quoted.matcher(message);
            while (matcher.find()) {
                final String suggested = matcher.group(1);
                assertThatCode(() -> StrictAddresses.parse(suggested, false))
                        .as("'%s' suggested for '%s' must itself parse", suggested, value)
                        .doesNotThrowAnyException();
            }
        }
    }

    /** No operator with a well-formed inventory sees any difference. */
    @Test
    void cidrAndHostSpellingsAreUntouched() {
        assertThatCode(() -> StrictAddresses.parse("10.20.0.0/16", false)).doesNotThrowAnyException();
        assertThatCode(() -> StrictAddresses.parse("10.20.30.7", false)).doesNotThrowAnyException();
        assertThatCode(() -> StrictAddresses.parse("10.20.30.7", true)).doesNotThrowAnyException();
        assertThatCode(() -> StrictAddresses.parse("2001:db8::1", false)).doesNotThrowAnyException();
        assertThatCode(() -> StrictAddresses.parse("2001:db8::/64", false)).doesNotThrowAnyException();
    }

    /**
     * The loader wraps the diagnosis with the entry naming, so the operator reads which
     * entry, which rule, and the fix in one sentence — and the width-rule bypass is dead:
     * the netmask "range" that used to collapse to a lone host never reaches the rule.
     */
    @Test
    void theLoaderNamesTheEntryAroundTheDiagnosis() {
        assertThatThrownBy(() -> InventoryLoader.parse(
                new SnmpProfilesConfig(java.util.Map.of(), java.util.Map.of()), """
                riptide:
                  snmp:
                    agents:
                      "10.90.0.0/255.0.255.0":
                        enabled: false
                """, "test.yaml"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("agent range")
                .hasMessageContaining("10.90.0.0/255.0.255.0")
                .hasMessageContaining("non-contiguous netmask");
    }

    /** The tightened single-segment and prefix-length rules, honest to the javadoc. */
    @Test
    void singleSegmentAndPrefixLengthZeroFormsAreRejected() {
        // a lone 0 and a 20-digit base85 string both parsed as addresses before
        assertThatThrownBy(() -> StrictAddresses.parse("0", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StrictAddresses.parse("00000000000000000000", false))
                .isInstanceOf(IllegalArgumentException.class);
        // the any-width spellings must survive every tightening: the library's
        // prefix-length-zeros flag reads the lone 0 in /0 as a leading zero, which is why
        // /016 stays accepted rather than /0 getting broken
        assertThatCode(() -> StrictAddresses.parse("0.0.0.0/0", false)).doesNotThrowAnyException();
        assertThatCode(() -> StrictAddresses.parse("::/0", false)).doesNotThrowAnyException();
    }
}
