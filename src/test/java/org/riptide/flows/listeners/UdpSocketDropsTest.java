/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.listeners;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.StandardSocketOptions;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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

    // ------------------------------------------------------------ real-kernel verification
    //
    // Everything above tests the parser against fixture lines the same author wrote — parser and
    // expectations are circular. The tests below close the loop against the kernel's actual
    // rendering. Note the wildcard test is deliberately the weakest: an ephemeral bind's address
    // half is all zeros, and all zeros is byte-order invariant, so only the 127.0.0.1 and ::1
    // binds pin the word-swapped encoding.

    /**
     * Pins the v4 byte order against the real kernel: {@code 127.0.0.1} renders as {@code 0100007F}
     * only if the word swap is right, and the address is non-palindromic so the wrong order cannot
     * accidentally match. An explicit {@code INET} socket pins the {@code /proc/net/udp} rendering;
     * a default (dual-stack) socket pins the v4-mapped rendering in {@code /proc/net/udp6} — both
     * halves of {@code procAddressForms}' defensive union.
     */
    @Test
    @EnabledOnOs(OS.LINUX)
    void kernelRenderingMatchesForALoopbackV4Bind() throws IOException {
        try (var v4only = DatagramChannel.open(StandardProtocolFamily.INET)) {
            v4only.bind(new InetSocketAddress(addr("127.0.0.1"), 0));

            assertThat(UdpSocketDrops.forSocket((InetSocketAddress) v4only.getLocalAddress()))
                    .as("null here means the word-swapped v4 encoding disagrees with the kernel")
                    .isEqualTo(0L);
        }

        try (var dualStack = DatagramChannel.open()) {
            dualStack.bind(new InetSocketAddress(addr("127.0.0.1"), 0));

            assertThat(UdpSocketDrops.forSocket((InetSocketAddress) dualStack.getLocalAddress()))
                    .as("null here means the v4-mapped-in-v6 form disagrees with the kernel")
                    .isEqualTo(0L);
        }
    }

    /** Pins the v6 word grouping: the single set bit of {@code ::1} lands differently under every
     *  wrong grouping, so an all-zeros coincidence cannot mask a mistake. */
    @Test
    @EnabledOnOs(OS.LINUX)
    void kernelRenderingMatchesForALoopbackV6Bind() throws IOException {
        final DatagramChannel channel;
        try {
            channel = DatagramChannel.open(StandardProtocolFamily.INET6);
            channel.bind(new InetSocketAddress(addr("::1"), 0));
        } catch (final UnsupportedOperationException | IOException e) {
            assumeTrue(false, "IPv6 loopback unavailable here: " + e);
            return;
        }
        try (channel) {
            assertThat(UdpSocketDrops.forSocket((InetSocketAddress) channel.getLocalAddress()))
                    .as("null here means the v6 word grouping disagrees with the kernel")
                    .isEqualTo(0L);
        }
    }

    /** Pins table selection and the port hex for the bind {@code UdpListener} actually performs.
     *  Weakest of the four on purpose — see the section comment. */
    @Test
    @EnabledOnOs(OS.LINUX)
    void kernelRenderingMatchesForAWildcardBind() throws IOException {
        try (var channel = DatagramChannel.open()) {
            channel.bind(new InetSocketAddress(0));

            assertThat(UdpSocketDrops.forSocket((InetSocketAddress) channel.getLocalAddress()))
                    .as("null here means the wildcard forms or the port hex disagree with the kernel")
                    .isEqualTo(0L);
        }
    }

    /**
     * The only test that pins the drops <em>column</em>. A zero assertion cannot: off by one the
     * parser reads {@code pointer}, which the kernel masks to zeros for unprivileged readers — and
     * that parses as 0 too. So this produces real drops: a minimal receive buffer (the kernel
     * clamps the request up to its floor, ~2304 bytes), a receiver that never reads, and ~100 KiB
     * of loopback datagrams. Loopback delivery enqueues synchronously in the sender's syscall, so
     * by the time the last send returns the kernel has counted the overflow — no sleeps, no races.
     */
    @Test
    @EnabledOnOs(OS.LINUX)
    void kernelCountedDropsAreTheNumberWeRead() throws IOException {
        try (var receiver = DatagramChannel.open(StandardProtocolFamily.INET);
             var sender = DatagramChannel.open(StandardProtocolFamily.INET)) {
            receiver.setOption(StandardSocketOptions.SO_RCVBUF, 1);
            receiver.bind(new InetSocketAddress(addr("127.0.0.1"), 0));
            final var bound = (InetSocketAddress) receiver.getLocalAddress();

            final ByteBuffer payload = ByteBuffer.allocate(1024);
            for (int i = 0; i < 100; i++) {
                payload.clear();
                sender.send(payload, bound);
            }

            assertThat(UdpSocketDrops.forSocket(bound))
                    .as("the kernel counted receive-buffer drops; we must read that same counter")
                    .isGreaterThan(0L);
        }
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
