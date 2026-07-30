/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.listeners;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Column arithmetic for the kernel's per-socket drop counter.
 *
 * <p>Worth testing precisely because every failure mode here is silent: the port is hex, so decimal
 * parsing finds nothing and reads as "no drops"; {@code drops} is the last of thirteen columns, so an
 * off-by-one lands on {@code pointer} and reports an address as a drop count; and a wildcard bind can
 * appear in both the v4 and v6 tables, so summing wrongly double-counts. Each of those produces a
 * plausible number rather than an error.
 *
 * <p>Fixtures are real {@code /proc/net/udp} lines. Port {@code 0x270F} is 9999, riptide's default.
 */
class UdpSocketDropsTest {

    private static final String HEADER =
            "   sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode ref pointer drops";

    /** A socket row bound to {@code port} (hex) reporting {@code drops}. */
    private static String row(final String hexPort, final long drops, final int inode) {
        return "  123: 00000000:" + hexPort + " 00000000:0000 07 00000000:00000000 00:00000000 00000000"
                + "     0        0 " + inode + " 2 0000000000000000 " + drops;
    }

    @Test
    void readsTheDropsColumnForTheMatchingHexPort() {
        final var lines = List.of(HEADER, row("270F", 42, 34567));

        assertThat(UdpSocketDrops.sumDrops(lines, 9999))
                .as("0x270F is 9999 — the port must be parsed as hex, not decimal")
                .isEqualTo(42L);
    }

    @Test
    void sumsAcrossEverySocketBoundToThePort() {
        // SO_REUSEPORT fan-out: several sockets, one port, drops must total rather than take the first
        final var lines = List.of(HEADER, row("270F", 10, 1), row("270F", 7, 2), row("270F", 5, 3));

        assertThat(UdpSocketDrops.sumDrops(lines, 9999)).isEqualTo(22L);
    }

    @Test
    void ignoresSocketsOnOtherPorts() {
        final var lines = List.of(HEADER, row("0035", 999, 1), row("270F", 3, 2), row("1F90", 999, 3));

        assertThat(UdpSocketDrops.sumDrops(lines, 9999))
                .as("another service's drops must never be attributed to this listener")
                .isEqualTo(3L);
    }

    @Test
    void returnsNullWhenNoSocketIsBoundToThePort() {
        final var lines = List.of(HEADER, row("0035", 12, 1));

        assertThat(UdpSocketDrops.sumDrops(lines, 9999))
                .as("no socket means no drop count — distinct from a socket reporting zero")
                .isNull();
    }

    @Test
    void aBoundSocketWithNoDropsReportsZeroNotNull() {
        assertThat(UdpSocketDrops.sumDrops(List.of(HEADER, row("270F", 0, 1)), 9999))
                .as("zero drops is a real, useful reading and must be distinguishable from absent")
                .isEqualTo(0L);
    }

    @Test
    void toleratesTheHeaderAndAnyMalformedRow() {
        final var lines = List.of(HEADER, "", "garbage", "  1: nonsense", row("270F", 4, 1));

        assertThat(UdpSocketDrops.sumDrops(lines, 9999))
                .as("a metrics scrape must not throw on unexpected procfs content")
                .isEqualTo(4L);
    }

    @Test
    void procfsAbsenceYieldsNullRatherThanZero() {
        // On a machine without /proc/net/udp (macOS, the usual dev box) the gauge must publish no
        // value. Reporting 0 there would assert "no kernel drops" on a platform that cannot know.
        final boolean linux = System.getProperty("os.name", "").toLowerCase().contains("linux");
        if (!linux) {
            assertThat(UdpSocketDrops.forPort(9999)).isNull();
        } else {
            // on Linux the file exists; an unbound port still has no row, so null is expected
            assertThat(UdpSocketDrops.forPort(1)).isNull();
        }
    }
}
