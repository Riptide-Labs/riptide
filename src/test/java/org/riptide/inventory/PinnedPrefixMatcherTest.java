/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.inventory;

import inet.ipaddr.IPAddressString;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Builder contracts and legacy address shapes. The matching semantics themselves are
 * pinned by the characterisation contract, bound to this component through
 * {@code PinnedPrefixMatcherSemanticsTest} in {@code org.riptide.node}.
 *
 * <p>The legacy-shape tests are load-bearing compatibility guarantees: these forms
 * (host-bits-set prefixes, ranges, wildcard formats, the {@code *} catch-all) were
 * accepted and matched by the pre-trie scan, and must keep their exact semantics
 * until the 0.9 loader makes configuration parsing strict.</p>
 */
class PinnedPrefixMatcherTest {

    @Test
    void duplicatePrefixAndPinFailsNamingBothEntries() {
        final var builder = PinnedPrefixMatcher.<String>builder()
                .add("site-a", prefix("10.20.30.0/24"), null, "a");

        assertThatThrownBy(() -> builder.add("backup", prefix("10.20.30.0/24"), null, "b"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("site-a")
                .hasMessageContaining("backup");
    }

    @Test
    void bareHostAndItsExplicitSlash32FailNamingBothEntries() {
        // distinct canonical strings but the same trie slot: the displaced-value check
        // catches what the string key cannot (previously a silent nondeterministic winner)
        final var builder = PinnedPrefixMatcher.<String>builder()
                .add("bare", prefix("10.0.0.7"), null, "a");

        assertThatThrownBy(() -> builder.add("explicit", prefix("10.0.0.7/32"), null, "b"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bare")
                .hasMessageContaining("explicit");
    }

    @Test
    void builderIsSingleShot() {
        final var builder = PinnedPrefixMatcher.<String>builder()
                .add("only", prefix("10.0.0.0/24"), null, "only");
        builder.build();

        assertThatThrownBy(() -> builder.add("late", prefix("10.1.0.0/24"), null, "late"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(builder::build).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void hostBitsSetPrefixMatchesOnlyItsExactAddress() {
        // 10.0.1.5/24 is a single address with a prefix, not the /24 block; the
        // pre-trie contains() matched only 10.0.1.5 and coarser entries caught the rest
        final var matcher = PinnedPrefixMatcher.<String>builder()
                .add("typo", prefix("10.0.1.5/24"), null, "typo")
                .add("site", prefix("10.0.0.0/16"), null, "site")
                .build();

        assertThat(matcher.lookup(probe("10.0.1.5"), 0)).hasValue("typo");
        assertThat(matcher.lookup(probe("10.0.1.9"), 0)).hasValue("site");
    }

    @Test
    void rangeSyntaxBuildsAndKeepsMatching() {
        final var matcher = PinnedPrefixMatcher.<String>builder()
                .add("edge", prefix("10.0.1-3.*"), null, "edge")
                .build();

        assertThat(matcher.lookup(probe("10.0.2.5"), 0)).hasValue("edge");
        assertThat(matcher.lookup(probe("10.0.4.5"), 0)).isEmpty();
    }

    @Test
    void pinnedLegacyShapeKeepsThePartitionSemantics() {
        // side-pool entries live in per-domain pools like trie entries: a pinned
        // range wins its own domain and is in neither pool for a foreign one
        final var matcher = PinnedPrefixMatcher.<String>builder()
                .add("pinned-range", prefix("10.0.1-3.*"), 42L, "pinned-range")
                .add("wildcard", prefix("10.0.0.0/16"), null, "wildcard")
                .build();

        assertThat(matcher.lookup(probe("10.0.2.5"), 42)).hasValue("pinned-range");
        assertThat(matcher.lookup(probe("10.0.2.5"), 7)).hasValue("wildcard");
    }

    @Test
    void duplicateFailurePoisonsTheBuilder() {
        final var builder = PinnedPrefixMatcher.<String>builder()
                .add("site-a", prefix("10.20.30.0/24"), null, "a");
        assertThatThrownBy(() -> builder.add("backup", prefix("10.20.30.0/24"), null, "b"))
                .isInstanceOf(IllegalStateException.class);

        // a caller that catches and continues must not obtain a half-corrupted matcher
        assertThatThrownBy(builder::build).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void nullPrefixFailsNamingTheEntry() {
        final var builder = PinnedPrefixMatcher.<String>builder();

        assertThatThrownBy(() -> builder.add("broken", null, null, "broken"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("broken");
    }

    @Test
    void wildcardFormatKeepsItsMostSpecificRank() {
        // the pre-trie ranking scored 10.0.*.* at full bit count (no explicit prefix
        // length), so it beat finer CIDR entries; preserved, not endorsed
        final var matcher = PinnedPrefixMatcher.<String>builder()
                .add("legacy", prefix("10.0.*.*"), null, "legacy")
                .add("fine", prefix("10.0.30.0/24"), null, "fine")
                .build();

        assertThat(matcher.lookup(probe("10.0.30.5"), 0)).hasValue("legacy");
        assertThat(matcher.lookup(probe("192.168.1.1"), 0)).isEmpty();
    }

    @Test
    void starCatchAllMatchesEverythingAtLowestRank() {
        final var matcher = PinnedPrefixMatcher.<String>builder()
                .add("catchall", prefix("*"), null, "catchall")
                .add("subnet", prefix("10.0.0.0/24"), null, "subnet")
                .build();

        assertThat(matcher.lookup(probe("203.0.113.9"), 0)).hasValue("catchall");
        assertThat(matcher.lookup(probe("10.0.0.5"), 0)).hasValue("subnet");
    }

    @Test
    void unparseableEntryMatchesNothing() {
        // today's silently-dead-entry behaviour, preserved until the 0.9 loader is strict
        final var matcher = PinnedPrefixMatcher.<String>builder()
                .add("broken", prefix("not-an-ip"), null, "broken")
                .add("valid", prefix("10.0.0.0/24"), null, "valid")
                .build();

        assertThat(matcher.lookup(probe("10.0.0.1"), 0)).hasValue("valid");
        assertThat(matcher.lookup(probe("192.168.1.1"), 0)).isEmpty();
    }

    @Test
    void nullProbeYieldsEmpty() {
        final var matcher = PinnedPrefixMatcher.<String>builder()
                .add("wildcard", prefix("10.0.0.0/24"), null, "wildcard")
                .build();

        assertThat(matcher.lookup(null, 0)).isEmpty();
    }

    private static IPAddressString prefix(final String value) {
        return new IPAddressString(value);
    }

    private static IPAddressString probe(final String value) {
        return new IPAddressString(value);
    }
}
