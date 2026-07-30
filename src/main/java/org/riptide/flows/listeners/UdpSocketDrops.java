/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.listeners;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Datagrams the kernel discarded because the socket receive buffer was full, read per socket from
 * {@code /proc/net/udp} and {@code /proc/net/udp6}.
 *
 * <p>This is the one loss that no application counter can see. Once the receive buffer overflows the
 * datagram is gone before any userspace code runs, so a collector can be losing a quarter of its
 * offered load while every metric it publishes reads healthy. Measured on the benchmark lab: at
 * saturation the application accounted for roughly 4% of a ~25% shortfall, and nothing accounted for
 * the rest.
 *
 * <p><strong>Per socket, not per host.</strong> {@code /proc/net/snmp}'s {@code Udp: RcvbufErrors} is
 * a host-wide total and cannot attribute drops to a receiver; the {@code drops} column of
 * {@code /proc/net/udp} can. Both the IPv4 and IPv6 tables are consulted because a wildcard bind on
 * Linux commonly yields a single v6 socket that also serves v4, and the value is <em>summed</em>
 * across every socket bound to the port so that {@code SO_REUSEPORT} fan-out is counted once in
 * total rather than once per socket.
 *
 * <p>Linux-only by construction. Where the files are absent — macOS, or a kernel without procfs —
 * every read returns {@code null} and the gauge simply publishes no value, which is honest: "not
 * measurable here" is not the same as "zero drops".
 *
 * <p>Cost is a small file read per call, so this is fine for a metrics scrape and must not be called
 * on the packet path.
 */
final class UdpSocketDrops {

    private static final Logger LOG = LoggerFactory.getLogger(UdpSocketDrops.class);

    private static final Path UDP4 = Path.of("/proc/net/udp");
    private static final Path UDP6 = Path.of("/proc/net/udp6");

    /** {@code sl local_address rem_address st tx_queue rx_queue ... inode ref pointer drops} */
    private static final int LOCAL_ADDRESS = 1;
    private static final int MIN_FIELDS = 13;

    private UdpSocketDrops() {
    }

    /**
     * Total dropped datagrams across all UDP sockets bound to {@code port}.
     *
     * @param port the local port to attribute drops to
     * @return the summed drop count, or {@code null} when procfs is unavailable or no socket is bound
     *         to the port (a closed socket has no drop count, which is distinct from having zero)
     */
    static Long forPort(final int port) {
        long total = 0;
        boolean found = false;

        for (final Path table : List.of(UDP4, UDP6)) {
            final Long drops = dropsIn(table, port);
            if (drops != null) {
                total += drops;
                found = true;
            }
        }

        return found ? total : null;
    }

    private static Long dropsIn(final Path table, final int port) {
        if (!Files.isReadable(table)) {
            return null;
        }

        try {
            return sumDrops(Files.readAllLines(table, StandardCharsets.US_ASCII), port);
        } catch (final IOException e) {
            // procfs reads can fail transiently; a metrics scrape must never propagate that
            LOG.debug("Could not read {} for socket drop counts", table, e);
            return null;
        }
    }

    /**
     * Sum the {@code drops} column over every row bound to {@code port}.
     *
     * <p>Package-private and taking lines rather than a path so the column arithmetic is testable
     * without procfs — the parsing (hex port, last column, summing across sockets) is the part that
     * can silently produce a plausible wrong number.
     *
     * @return summed drops, or {@code null} if no row matched
     */
    static Long sumDrops(final List<String> lines, final int port) {
        long total = 0;
        boolean found = false;

        for (final String line : lines) {
            final String[] fields = line.trim().split("\\s+");
            // the header line splits fine but has no numeric port, so it falls out on the port check
            if (fields.length < MIN_FIELDS) {
                continue;
            }
            if (localPort(fields[LOCAL_ADDRESS]) != port) {
                continue;
            }
            final long drops = parseUnsigned(fields[fields.length - 1]);
            if (drops >= 0) {
                total += drops;
                found = true;
            }
        }

        return found ? total : null;
    }

    /** {@code local_address} is {@code <hex-addr>:<hex-port>}; only the port is needed. */
    private static int localPort(final String localAddress) {
        final int colon = localAddress.lastIndexOf(':');
        if (colon < 0 || colon == localAddress.length() - 1) {
            return -1;
        }
        try {
            return Integer.parseInt(localAddress.substring(colon + 1), 16);
        } catch (final NumberFormatException e) {
            return -1;
        }
    }

    private static long parseUnsigned(final String value) {
        try {
            return Long.parseLong(value);
        } catch (final NumberFormatException e) {
            return -1;
        }
    }
}
