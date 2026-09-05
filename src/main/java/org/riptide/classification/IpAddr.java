/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.classification;

import com.google.common.net.InetAddresses;
import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Objects;

public abstract class IpAddr implements Comparable<IpAddr> {

    public static IpAddr of(String dottedNotation) {
        if (dottedNotation == null) {
            return null;
        }
        try {
            return of(InetAddress.getByName(dottedNotation));
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Invalid IPAddress " + dottedNotation, e);
        }
    }

    /**
     * Converts an address, answering {@code null} for an absent one.
     * <p>
     * Absent is ordinary input on the flow path rather than an error. A NetFlow v9 or IPFIX template
     * need not carry an address field at all, and both builders answer {@code null} when it is missing;
     * {@code LocalityEnricher}, {@code GeoIpEnricher} and {@code RoutingEnricher} each test that same
     * field for null before reading it. {@code ClassificationEnricher} hands whatever it gets straight
     * to this method, and every consumer downstream of it already treats a null address as "this flow
     * has no address": {@link ClassificationRequest} holds one, and
     * {@code IpMatcher} answers "no match" for it.
     * <p>
     * The guard lives here rather than at the three call sites because the {@link #of(String)} overload
     * above already answers {@code null} for an absent address. The two overloads disagreeing on the
     * same question is what made this throw, so they are made to agree rather than having each caller
     * remember which one it is holding.
     */
    public static IpAddr of(InetAddress addr) {
        if (addr == null) {
            return null;
        }
        var bytes = addr.getAddress();
        if (bytes.length == 4) {
            return new Ip4Addr(
                    Ints.fromBytes(bytes[0], bytes[1], bytes[2], bytes[3])
            );
        } else if (bytes.length == 16) {
            return new Ip6Addr(
                Longs.fromBytes(bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5], bytes[6], bytes[7]),
                Longs.fromBytes(bytes[8], bytes[9], bytes[10], bytes[11], bytes[12], bytes[13], bytes[14], bytes[15])
            );
        } else {
            throw new RuntimeException("unexpected number of bytes of ip address - addr: " + addr);
        }
    }

    public abstract IpAddr inc();

    public static final class Ip4Addr extends IpAddr {
        private final int value;

        public Ip4Addr(int value) {
            this.value = value;
        }

        @Override
        public int compareTo(IpAddr other) {
            if (other instanceof Ip6Addr) {
                return -1;
            }
            var o = (Ip4Addr) other;
            return Integer.compareUnsigned(value, o.value);
        }

        @Override
        public IpAddr inc() {
            if (value + 1 == 0) {
                throw new RuntimeException("IP address can no more; limit reached");
            }
            return new Ip4Addr(value + 1);
        }

        @Override
        public String toString() {
            return InetAddresses.fromInteger(value).getHostAddress();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            Ip4Addr ip4Addr = (Ip4Addr) o;
            return value == ip4Addr.value;
        }

        @Override
        public int hashCode() {
            return Objects.hash(value);
        }
    }

    public static final class Ip6Addr extends IpAddr {
        private final long high, low;

        public Ip6Addr(long high, long low) {
            this.high = high;
            this.low = low;
        }

        @Override
        public int compareTo(IpAddr other) {
            if (other instanceof Ip4Addr) {
                return 1;
            }
            var o = (Ip6Addr) other;
            return high != o.high
                    ? Long.compareUnsigned(high, o.high)
                    : Long.compareUnsigned(low, o.low);

        }

        @Override
        public IpAddr inc() {
            if (low + 1 == 0) {
                if (high + 1 == 0) {
                    throw new RuntimeException("IP address can no more; limit reached");
                }
                return new Ip6Addr(high + 1, 0);
            } else {
                return new Ip6Addr(high, low + 1);
            }
        }

        @Override
        public String toString() {
            final var bytes = new byte[16];
            System.arraycopy(Longs.toByteArray(high), 0, bytes, 0, 8);
            System.arraycopy(Longs.toByteArray(low), 0, bytes, 8, 8);

            try {
                return InetAddress.getByAddress(bytes).getHostAddress();
            } catch (final UnknownHostException e) {
                throw new AssertionError(e);
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            Ip6Addr ip6Addr = (Ip6Addr) o;
            return high == ip6Addr.high && low == ip6Addr.low;
        }

        @Override
        public int hashCode() {
            return Objects.hash(high, low);
        }
    }
}
