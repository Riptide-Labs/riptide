/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.inventory;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
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

    /** Wrong shape after a clean parse keeps the original messages too. */
    @Test
    void wrongShapeKeepsTheOriginalMessages() {
        // host bits set with a prefix: parses, but is neither host nor block
        assertThatThrownBy(() -> StrictAddresses.parse("10.0.1.5/24", false))
                .hasMessage("is not a host address or CIDR prefix.");
        // a range where only a host is allowed
        assertThatThrownBy(() -> StrictAddresses.parse("10.0.0.0/24", true))
                .hasMessage("is not a single host address.");
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

    /** The shared utility means the parameters cannot drift between loader and converter. */
    @Test
    void thereIsExactlyOneStrictParameterSet() {
        assertThat(StrictAddresses.STRICT).isNotNull();
        // the loader and converter both route through StrictAddresses.parse; pinned by the
        // converter-side diagnosis test in LegacyConverterTest rather than reflection here
    }
}
