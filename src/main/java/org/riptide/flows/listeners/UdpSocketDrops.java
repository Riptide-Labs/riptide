/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.listeners;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
 * {@code /proc/net/udp} can. Rows are matched on the bound <em>address and port</em>, not the port
 * alone: two receivers may legitimately share a port on different addresses, and matching by port
 * would attribute each one's kernel drops to both. Both the IPv4 and IPv6 tables are consulted
 * because a wildcard bind on Linux commonly yields a single v6 socket that also serves v4, and the
 * value is <em>summed</em> across matching rows so that {@code SO_REUSEPORT} fan-out is counted once
 * in total rather than once per socket.
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

    /** The all-zeros address halves a wildcard bind shows in each table. */
    private static final String ANY_V4 = "00000000";
    private static final String ANY_V6 = "0".repeat(32);

    private UdpSocketDrops() {
    }

    /**
     * Total dropped datagrams across all UDP sockets bound to exactly this address and port.
     *
     * @param bound the socket address actually bound, as reported by the channel
     * @return the summed drop count, or {@code null} when procfs is unavailable or no matching socket
     *         exists (a closed socket has no drop count, which is distinct from having zero)
     */
    static Long forSocket(final InetSocketAddress bound) {
        final Set<String> addresses = procAddressForms(bound.getAddress());
        final int port = bound.getPort();

        long total = 0;
        boolean found = false;

        for (final Path table : List.of(UDP4, UDP6)) {
            final Long drops = dropsIn(table, addresses, port);
            if (drops != null) {
                total += drops;
                found = true;
            }
        }

        return found ? total : null;
    }

    private static Long dropsIn(final Path table, final Set<String> addresses, final int port) {
        if (!Files.isReadable(table)) {
            return null;
        }

        try {
            return sumDrops(Files.readAllLines(table, StandardCharsets.US_ASCII), addresses, port);
        } catch (final IOException e) {
            // procfs reads can fail transiently; a metrics scrape must never propagate that
            LOG.debug("Could not read {} for socket drop counts", table, e);
            return null;
        }
    }

    /**
     * Sum the {@code drops} column over every row whose local address half is in {@code addresses}
     * and whose local port is {@code port}.
     *
     * <p>Package-private and taking lines rather than a path so the column arithmetic is testable
     * without procfs — the parsing (hex port, byte-swapped hex addresses, last column, summing across
     * sockets) is the part that can silently produce a plausible wrong number.
     *
     * @return summed drops, or {@code null} if no row matched
     */
    static Long sumDrops(final List<String> lines, final Set<String> addresses, final int port) {
        long total = 0;
        boolean found = false;

        for (final String line : lines) {
            final String[] fields = line.trim().split("\\s+");
            // the header line splits fine but has no numeric port, so it falls out on the port check
            if (fields.length < MIN_FIELDS) {
                continue;
            }
            if (!matchesLocal(fields[LOCAL_ADDRESS], addresses, port)) {
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

    /**
     * The hex address halves that represent {@code address} in the procfs tables.
     *
     * <p>procfs prints each 32-bit word of the address byte-swapped, so {@code 10.0.0.2} appears as
     * {@code 0200000A}, not {@code 0A000002}. A wildcard bind matches the all-zeros form in either
     * table. A specific IPv4 bind may appear either as an AF_INET socket in {@code /proc/net/udp} or,
     * because the JDK opens dual-stack sockets by default, as the v4-mapped form
     * ({@code ::ffff:a.b.c.d}) in {@code /proc/net/udp6} — both forms are accepted.
     */
    static Set<String> procAddressForms(final InetAddress address) {
        if (address == null || address.isAnyLocalAddress()) {
            return Set.of(ANY_V4, ANY_V6);
        }
        if (address instanceof Inet4Address) {
            final String v4 = wordSwappedHex(address.getAddress());
            final byte[] mapped = new byte[16];
            mapped[10] = (byte) 0xFF;
            mapped[11] = (byte) 0xFF;
            System.arraycopy(address.getAddress(), 0, mapped, 12, 4);
            return Set.of(v4, wordSwappedHex(mapped));
        }
        if (address instanceof Inet6Address) {
            return Set.of(wordSwappedHex(address.getAddress()));
        }
        return Set.of();
    }

    private static boolean matchesLocal(final String localAddress, final Set<String> addresses, final int port) {
        final int colon = localAddress.lastIndexOf(':');
        if (colon <= 0 || colon == localAddress.length() - 1) {
            return false;
        }
        if (localPort(localAddress, colon) != port) {
            return false;
        }
        return addresses.contains(localAddress.substring(0, colon).toUpperCase(Locale.ROOT));
    }

    /**
     * Hex-encode {@code bytes} with each 4-byte word byte-swapped, matching procfs's rendering.
     *
     * <p>The loop bound requires a complete word before reading one, so the method is total even
     * though every real input ({@link InetAddress#getAddress()}) is 4 or 16 bytes: relying on the
     * call sites for the bounds proof is exactly how a refactor earns an
     * {@code ArrayIndexOutOfBoundsException} later.
     */
    private static String wordSwappedHex(final byte[] bytes) {
        final StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (int word = 0; word + 3 < bytes.length; word += 4) {
            for (int i = word + 3; i >= word; i--) {
                hex.append(String.format(Locale.ROOT, "%02X", bytes[i]));
            }
        }
        return hex.toString();
    }

    /** {@code local_address} is {@code <hex-addr>:<hex-port>}; only the port is needed here. */
    private static int localPort(final String localAddress, final int colon) {
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
