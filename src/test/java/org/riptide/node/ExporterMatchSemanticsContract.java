/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.node;

import org.junit.jupiter.api.Test;
import org.riptide.pipeline.ExporterIdentity;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Characterisation of the exporter-matching semantics, pinned before any refactor
 * touches them. Every test drives the {@link ExporterMatchSemantics} seam only; each
 * implementation binds the suite by subclassing and providing its fixture, so the test
 * bodies are the contract and never change.
 *
 * <p>Deliberately unpinned accidents (not semantics): host-bits-set prefixes,
 * unparseable subnet strings matching nothing silently, and validation message prose
 * beyond naming the offending entries. A future implementation may fix any of these
 * without failing this suite. One such fix has landed: a bare host and its explicit
 * {@code /32} spelling, formerly an undetected ambiguity with a nondeterministic
 * winner, are now rejected at build time naming both entries.</p>
 */
abstract class ExporterMatchSemanticsContract {

    protected abstract ExporterMatchSemantics semantics();

    @Test
    void longestPrefixWinsInEitherDeclarationOrder() {
        final var coarseFirst = build(
                entry("coarse", "10.20.0.0/16", null),
                entry("fine", "10.20.30.0/24", null));
        final var fineFirst = build(
                entry("fine", "10.20.30.0/24", null),
                entry("coarse", "10.20.0.0/16", null));

        assertThat(coarseFirst.matchedName(netflow("10.20.30.5", 0))).hasValue("fine");
        assertThat(fineFirst.matchedName(netflow("10.20.30.5", 0))).hasValue("fine");
        // outside the /24, the /16 still matches
        assertThat(coarseFirst.matchedName(netflow("10.20.99.5", 0))).hasValue("coarse");
    }

    @Test
    void pinnedCoarsePrefixBeatsWildcardFinerPrefix() {
        // pinning partitions the candidate pool before prefix length ranks it: any
        // pinned match excludes every wildcard, even a more specific one
        final var matcher = build(
                entry("fine-wildcard", "10.0.30.0/24", null),
                entry("coarse-pinned", "10.0.0.0/16", 42L));

        assertThat(matcher.matchedName(netflow("10.0.30.5", 42))).hasValue("coarse-pinned");
        assertThat(matcher.matchedName(netflow("10.0.30.5", 7))).hasValue("fine-wildcard");
    }

    @Test
    void entryPinnedToAnotherDomainIsExcludedFromBothPools() {
        final var pinnedOnly = build(entry("pinned", "10.0.0.0/24", 42L));

        assertThat(pinnedOnly.matchedName(netflow("10.0.0.1", 7))).isEmpty();

        // the pinned /24 never degrades to wildcard behaviour for a foreign domain
        final var withWildcard = build(
                entry("pinned-fine", "10.0.0.0/24", 42L),
                entry("wildcard-coarse", "10.0.0.0/16", null));

        assertThat(withWildcard.matchedName(netflow("10.0.0.1", 7))).hasValue("wildcard-coarse");
        assertThat(withWildcard.matchedName(netflow("10.0.0.1", 42))).hasValue("pinned-fine");
    }

    @Test
    void bareHostAddressBeatsAnyPrefix() {
        final var matcher = build(
                entry("subnet", "10.0.0.0/24", null),
                entry("host", "10.0.0.7", null));

        assertThat(matcher.matchedName(netflow("10.0.0.7", 0))).hasValue("host");
        assertThat(matcher.matchedName(netflow("10.0.0.8", 0))).hasValue("subnet");
    }

    @Test
    void ipv6LongestPrefixWins() {
        final var matcher = build(
                entry("coarse", "2001:db8::/32", null),
                entry("fine", "2001:db8:1::/48", null));

        assertThat(matcher.matchedName(netflow("2001:db8:1::5", 0))).hasValue("fine");
        assertThat(matcher.matchedName(netflow("2001:db8:2::5", 0))).hasValue("coarse");
    }

    @Test
    void ipv6BareHostAddressIsMostSpecific() {
        final var matcher = build(
                entry("subnet", "2001:db8::/64", null),
                entry("host", "2001:db8::7", null));

        assertThat(matcher.matchedName(netflow("2001:db8::7", 0))).hasValue("host");
        assertThat(matcher.matchedName(netflow("2001:db8::8", 0))).hasValue("subnet");
    }

    @Test
    void subnetsNeverMatchAcrossAddressFamilies() {
        final var v4 = build(entry("v4", "10.0.0.0/8", null));
        final var v6 = build(entry("v6", "2001:db8::/32", null));

        assertThat(v4.matchedName(netflow("2001:db8::1", 0))).isEmpty();
        assertThat(v6.matchedName(netflow("10.0.0.1", 0))).isEmpty();
    }

    @Test
    void addressMatchingNoEntryYieldsEmpty() {
        final var matcher = build(entry("wildcard", "10.0.0.0/24", null));

        assertThat(matcher.matchedName(netflow("192.168.1.1", 0))).isEmpty();
    }

    @Test
    void sflowMatchesByPayloadAgentAddressWithSubAgentPin() {
        final var matcher = build(
                entry("wildcard", "10.1.0.0/16", null),
                entry("pinned", "10.1.0.0/16", 7L));

        assertThat(matcher.matchedName(sflow("10.1.1.1", 7))).hasValue("pinned");
        assertThat(matcher.matchedName(sflow("10.1.1.1", 3))).hasValue("wildcard");
    }

    @Test
    void exactTieFailsNamingBothEntries() {
        assertThatThrownBy(() -> build(
                entry("site-a", "10.20.30.0/24", null),
                entry("backup", "10.20.30.0/24", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("site-a")
                .hasMessageContaining("backup");
    }

    @Test
    void exactTieWithSamePinFailsNamingBothEntries() {
        assertThatThrownBy(() -> build(
                entry("primary", "10.20.30.0/24", 42L),
                entry("shadow", "10.20.30.0/24", 42L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("primary")
                .hasMessageContaining("shadow");
    }

    @Test
    void sameSubnetWithDistinctPinningsIsNotATie() {
        final var matcher = build(
                entry("pinned", "10.20.30.0/24", 42L),
                entry("wildcard", "10.20.30.0/24", null),
                entry("other-domain", "10.20.30.0/24", 7L));

        assertThat(matcher.matchedName(netflow("10.20.30.5", 42))).hasValue("pinned");
        assertThat(matcher.matchedName(netflow("10.20.30.5", 7))).hasValue("other-domain");
        assertThat(matcher.matchedName(netflow("10.20.30.5", 1))).hasValue("wildcard");
    }

    @Test
    void entryWithoutSubnetFailsNamingTheEntry() {
        assertThatThrownBy(() -> build(entry("broken", null, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("broken");
    }

    private ExporterMatchSemantics.Matcher build(final ExporterMatchSemantics.Entry... entries) {
        return semantics().build(List.of(entries));
    }

    private static ExporterMatchSemantics.Entry entry(final String name, final String subnet, final Long pin) {
        return new ExporterMatchSemantics.Entry(name, subnet, pin);
    }

    private static ExporterIdentity netflow(final String address, final long domain) {
        return new ExporterIdentity.NetflowIpfix(host(address), domain);
    }

    private static ExporterIdentity sflow(final String agentAddress, final long subAgentId) {
        return new ExporterIdentity.Sflow(host(agentAddress), subAgentId);
    }

    private static InetAddress host(final String address) {
        try {
            return InetAddress.getByName(address);
        } catch (final UnknownHostException e) {
            throw new IllegalArgumentException(address, e);
        }
    }
}
