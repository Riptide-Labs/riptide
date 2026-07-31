/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.listeners;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Column arithmetic for the kernel's per-socket drop counter.
 *
 * <p>Worth testing precisely because every failure mode here is silent: the port is hex, so decimal
 * parsing finds nothing and reads as "no drops"; the address halves are byte-swapped per 32-bit word,
 * so a straight hex encoding matches nothing; {@code drops} is the last of thirteen columns, so an
 * off-by-one lands on {@code pointer} and reports an address as a drop count; and rows must match on
 * address <em>and</em> port, or a co-located socket sharing the port number gets its loss attributed
 * to this listener. Each of those produces a plausible number rather than an error.
 *
 * <p>Fixtures are real {@code /proc/net/udp} lines. Port {@code 0x270F} is 9999, riptide's default.
 */
class UdpSocketDropsTest {

    private static final String HEADER =
            "   sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode ref pointer drops";

    private static final Set<String> WILDCARD = UdpSocketDrops.procAddressForms(null);

    /** A socket row bound to {@code hexAddr:hexPort} reporting {@code drops}. */
    private static String row(final String hexAddr, final String hexPort, final long drops, final int inode) {
        return "  123: " + hexAddr + ":" + hexPort + " 00000000:0000 07 00000000:00000000 00:00000000 00000000"
                + "     0        0 " + inode + " 2 0000000000000000 " + drops;
    }

    private static InetAddress addr(final String literal) {
        try {
            return InetAddress.getByName(literal);
        } catch (final UnknownHostException e) {
            throw new IllegalArgumentException(literal, e);
        }
    }

    @Test
    void readsTheDropsColumnForTheMatchingHexPort() {
        final var lines = List.of(HEADER, row("00000000", "270F", 42, 34567));

        assertThat(UdpSocketDrops.sumDrops(lines, WILDCARD, 9999))
                .as("0x270F is 9999 — the port must be parsed as hex, not decimal")
                .isEqualTo(42L);
    }

    @Test
    void sumsAcrossEverySocketBoundToTheSameAddressAndPort() {
        // SO_REUSEPORT fan-out: several sockets, one bind address, drops must total rather than take
        // the first
        final var lines = List.of(HEADER,
                row("00000000", "270F", 10, 1),
                row("00000000", "270F", 7, 2),
                row("00000000", "270F", 5, 3));

        assertThat(UdpSocketDrops.sumDrops(lines, WILDCARD, 9999)).isEqualTo(22L);
    }

    @Test
    void ignoresSocketsOnOtherPorts() {
        final var lines = List.of(HEADER,
                row("00000000", "0035", 999, 1),
                row("00000000", "270F", 3, 2),
                row("00000000", "1F90", 999, 3));

        assertThat(UdpSocketDrops.sumDrops(lines, WILDCARD, 9999))
                .as("another service's drops must never be attributed to this listener")
                .isEqualTo(3L);
    }

    @Test
    void ignoresSocketsSharingThePortOnAnotherAddress() {
        // Two receivers may legitimately share a port on different addresses (ReceiverConfig exposes
        // host + port per receiver, and nothing validates against duplicates). 10.0.0.2 is byte-swapped
        // to 0200000A in procfs; the sibling on 10.0.0.3 must not contribute.
        final var mine = UdpSocketDrops.procAddressForms(addr("10.0.0.2"));
        final var lines = List.of(HEADER,
                row("0200000A", "270F", 3, 1),   // 10.0.0.2:9999 — this listener
                row("0300000A", "270F", 999, 2)); // 10.0.0.3:9999 — a sibling receiver

        assertThat(UdpSocketDrops.sumDrops(lines, mine, 9999))
                .as("matching by port alone would attribute the sibling's loss to both receivers")
                .isEqualTo(3L);
    }

    @Test
    void aWildcardBindDoesNotClaimASpecificBindOnTheSamePort() {
        final var lines = List.of(HEADER,
                row("00000000", "270F", 4, 1),    // 0.0.0.0:9999 — this listener
                row("0200000A", "270F", 999, 2)); // 10.0.0.2:9999 — someone else's socket

        assertThat(UdpSocketDrops.sumDrops(lines, WILDCARD, 9999)).isEqualTo(4L);
    }

    @Test
    void aDualStackV4BindMatchesItsMappedFormInTheV6Table() {
        // The JDK opens dual-stack sockets by default, so a bind to 10.0.0.2 can appear in
        // /proc/net/udp6 as ::ffff:10.0.0.2 — word-swapped to ...FFFF0000 + 0200000A.
        final var mine = UdpSocketDrops.procAddressForms(addr("10.0.0.2"));
        final var lines = List.of(HEADER, row("0000000000000000FFFF00000200000A", "270F", 6, 1));

        assertThat(UdpSocketDrops.sumDrops(lines, mine, 9999)).isEqualTo(6L);
    }

    @Test
    void procAddressFormsEncodeTheWordSwappedRendering() {
        assertThat(UdpSocketDrops.procAddressForms(addr("127.0.0.1")))
                .as("127.0.0.1 renders as 0100007F, not 7F000001")
                .contains("0100007F");
        assertThat(UdpSocketDrops.procAddressForms(addr("::1")))
                .as("::1 renders with its final word swapped")
                .containsExactly("00000000000000000000000001000000");
        assertThat(UdpSocketDrops.procAddressForms(null))
                .as("a wildcard bind matches the all-zeros form in either table")
                .containsExactlyInAnyOrder("00000000", "0".repeat(32));
    }

    @Test
    void returnsNullWhenNoSocketMatches() {
        final var lines = List.of(HEADER, row("00000000", "0035", 12, 1));

        assertThat(UdpSocketDrops.sumDrops(lines, WILDCARD, 9999))
                .as("no socket means no drop count — distinct from a socket reporting zero")
                .isNull();
    }

    @Test
    void aBoundSocketWithNoDropsReportsZeroNotNull() {
        assertThat(UdpSocketDrops.sumDrops(List.of(HEADER, row("00000000", "270F", 0, 1)), WILDCARD, 9999))
                .as("zero drops is a real, useful reading and must be distinguishable from absent")
                .isEqualTo(0L);
    }

    @Test
    void toleratesTheHeaderAndAnyMalformedRow() {
        final var lines = List.of(HEADER, "", "garbage", "  1: nonsense", row("00000000", "270F", 4, 1));

        assertThat(UdpSocketDrops.sumDrops(lines, WILDCARD, 9999))
                .as("a metrics scrape must not throw on unexpected procfs content")
                .isEqualTo(4L);
    }

    @Test
    @DisabledOnOs(OS.LINUX)
    void procfsAbsenceYieldsNullRatherThanZero() {
        // Without /proc/net/udp the gauge must publish no value. Reporting 0 here would assert
        // "no kernel drops" on a platform that cannot know.
        assertThat(UdpSocketDrops.forSocket(new InetSocketAddress(9999))).isNull();
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void anUnboundSocketYieldsNullOnLinux() {
        // procfs is present but no socket is bound to UDP port 1: no row, so no drop count.
        assertThat(UdpSocketDrops.forSocket(new InetSocketAddress(1))).isNull();
    }
}
