/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.inventory;

import inet.ipaddr.IPAddressString;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The flow path matches by {@link InetAddress}; it used to format the address to text and
 * match the parsed {@link IPAddressString}. Both forms must resolve the same entry for every
 * address shape and every pool: trie and side pool, pinned and wildcard, both families. The
 * expected name column keeps the comparison from passing on two equally empty results.
 *
 * <p>An IPv6 scope zone is carried into the probe but never changes a result: inet.ipaddr's
 * tries and {@code contains} ignore zones (an entry {@code fe80::1%6} contains the probe
 * {@code fe80::1%5}). So the scoped row pins equality, not zone handling.</p>
 */
class PinnedPrefixMatcherAddressFormTest {

    private static final PinnedPrefixMatcher<String> MATCHER = PinnedPrefixMatcher.<String>builder()
            .add("v4-block", new IPAddressString("10.0.0.0/24"), null, "v4-block")
            .add("v4-host", new IPAddressString("10.0.0.7"), null, "v4-host")
            .add("v6-block", new IPAddressString("2001:db8::/64"), null, "v6-block")
            .add("v6-host", new IPAddressString("2001:db8::7"), null, "v6-host")
            .add("link-local", new IPAddressString("fe80::/64"), null, "link-local")
            .add("legacy", new IPAddressString("10.0.30.1/24"), null, "legacy")
            .add("range", new IPAddressString("10.0.40.1-9"), null, "range")
            .add("pinned-wide", new IPAddressString("10.0.0.0/16"), 42L, "pinned-wide")
            .add("catchall", new IPAddressString("*"), 99L, "catchall")
            .build();

    /** No side entries anywhere, the shape discovery produces: the direct form never builds text. */
    private static final PinnedPrefixMatcher<String> CIDR_ONLY = PinnedPrefixMatcher.<String>builder()
            .add("v4-block", new IPAddressString("10.0.0.0/24"), null, "v4-block")
            .add("v4-host", new IPAddressString("10.0.0.7"), null, "v4-host")
            .add("v6-host", new IPAddressString("2001:db8::7"), null, "v6-host")
            .add("pinned-host", new IPAddressString("10.0.0.9"), 42L, "pinned-host")
            .build();

    static Stream<Arguments> cidrOnlyAddresses() throws UnknownHostException {
        return Stream.of(
                Arguments.of("v4 host", InetAddress.getByName("10.0.0.7"), 0L, "v4-host"),
                Arguments.of("v4 in block", InetAddress.getByName("10.0.0.8"), 0L, "v4-block"),
                Arguments.of("pinned host", InetAddress.getByName("10.0.0.9"), 42L, "pinned-host"),
                Arguments.of("pinned pool misses, wildcard hits", InetAddress.getByName("10.0.0.8"), 42L, "v4-block"),
                Arguments.of("v4 miss", InetAddress.getByName("192.0.2.1"), 0L, null),
                Arguments.of("v6 host", InetAddress.getByName("2001:db8::7"), 0L, "v6-host"),
                Arguments.of("v6 miss", InetAddress.getByName("2001:db8::8"), 0L, null));
    }

    static Stream<Arguments> addresses() throws UnknownHostException {
        return Stream.of(
                Arguments.of("v4 host", InetAddress.getByName("10.0.0.7"), 0L, "v4-host"),
                Arguments.of("v4 in block", InetAddress.getByName("10.0.0.8"), 0L, "v4-block"),
                Arguments.of("pinned beats a more specific wildcard", InetAddress.getByName("10.0.0.7"), 42L,
                        "pinned-wide"),
                Arguments.of("pinned outside every wildcard", InetAddress.getByName("10.0.1.1"), 42L, "pinned-wide"),
                Arguments.of("other pin falls back to wildcard", InetAddress.getByName("10.0.0.8"), 7L, "v4-block"),
                Arguments.of("side pool, host bits set", InetAddress.getByName("10.0.30.1"), 0L, "legacy"),
                Arguments.of("side pool, host bits set, other host", InetAddress.getByName("10.0.30.2"), 0L, null),
                Arguments.of("side pool, range", InetAddress.getByName("10.0.40.5"), 0L, "range"),
                Arguments.of("side pool, catch-all", InetAddress.getByName("192.0.2.1"), 99L, "catchall"),
                Arguments.of("v4 miss", InetAddress.getByName("192.0.2.1"), 0L, null),
                Arguments.of("v6 host", InetAddress.getByName("2001:db8::7"), 0L, "v6-host"),
                Arguments.of("v6 in block", InetAddress.getByName("2001:db8::8"), 0L, "v6-block"),
                Arguments.of("v6 miss", InetAddress.getByName("2001:db9::1"), 0L, null),
                Arguments.of("v4-mapped v6 is an Inet4Address", InetAddress.getByName("::ffff:10.0.0.7"), 0L,
                        "v4-host"),
                // numeric scope id, not an interface name: a named zone resolves against the
                // host's interfaces and would make this row host-dependent
                Arguments.of("scoped v6", Inet6Address.getByAddress(null,
                        InetAddress.getByName("fe80::1").getAddress(), 5), 0L, "link-local"),
                Arguments.of("v6 never matches a v4 entry", InetAddress.getByName("::a00:7"), 0L, null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("addresses")
    void bothFormsResolveTheSameEntry(final String name, final InetAddress address, final long domain,
                                      final String expected) {
        final Optional<String> textual = MATCHER.lookup(new IPAddressString(address.getHostAddress()), domain);
        final Optional<String> direct = MATCHER.lookup(address, domain);

        assertThat(textual).isEqualTo(Optional.ofNullable(expected));
        assertThat(direct).isEqualTo(textual);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cidrOnlyAddresses")
    void bothFormsResolveTheSameEntryWithoutSideEntries(final String name, final InetAddress address,
                                                         final long domain, final String expected) {
        final Optional<String> textual = CIDR_ONLY.lookup(new IPAddressString(address.getHostAddress()), domain);
        final Optional<String> direct = CIDR_ONLY.lookup(address, domain);

        assertThat(textual).isEqualTo(Optional.ofNullable(expected));
        assertThat(direct).isEqualTo(textual);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("addresses")
    void anEmptyMatcherResolvesNothing(final String name, final InetAddress address, final long domain,
                                       final String expected) {
        final PinnedPrefixMatcher<String> empty = PinnedPrefixMatcher.<String>builder().build();

        assertThat(empty.lookup(address, domain)).isEmpty();
        assertThat(empty.lookup(new IPAddressString(address.getHostAddress()), domain)).isEmpty();
    }
}
