/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser.sflow.proto;

import io.netty.buffer.ByteBuf;

import java.net.InetAddress;

import static org.riptide.flows.utils.BufferUtils.inetAddress;
import static org.riptide.flows.utils.BufferUtils.uint32;

/**
 * The extended flow-record structures riptide consumes (sflow_version_5.txt §5.2.6):
 * switch (1001) for VLANs, router (1002) for next hop and masks, gateway (1003) for
 * BGP AS data — the latter feeds the enrichment ladder's "nonzero exporter AS wins"
 * rule. Each parses leniently: a malformed record yields {@code null} and the flow
 * keeps the rest of its data.
 */
public final class ExtendedData {

    public record Switch(int srcVlan, int dstVlan) {
        static Switch parse(final ByteBuf b) {
            if (b.readableBytes() < 16) {
                return null;
            }
            final int srcVlan = (int) uint32(b);
            uint32(b); // src priority
            final int dstVlan = (int) uint32(b);
            return new Switch(srcVlan, dstVlan);
        }
    }

    public record Router(InetAddress nextHop, int srcMaskLen, int dstMaskLen) {
        static Router parse(final ByteBuf b) {
            final InetAddress nextHop = address(b);
            if (nextHop == null || b.readableBytes() < 8) {
                return null;
            }
            return new Router(nextHop, (int) uint32(b), (int) uint32(b));
        }
    }

    public record Gateway(InetAddress nextHop, long srcAs, long dstAs) {
        static Gateway parse(final ByteBuf b) {
            final InetAddress nextHop = address(b);
            if (nextHop == null || b.readableBytes() < 12) {
                return null;
            }
            uint32(b); // the router's own AS
            final long srcAs = uint32(b);
            uint32(b); // src peer AS

            // dst_as_path: the destination AS is the last hop of the last segment
            long dstAs = 0;
            if (b.readableBytes() >= 4) {
                final long segments = uint32(b);
                for (long s = 0; s < segments && b.readableBytes() >= 8; s++) {
                    uint32(b); // segment type (AS_SET / AS_SEQUENCE)
                    final long count = uint32(b);
                    for (long i = 0; i < count && b.readableBytes() >= 4; i++) {
                        dstAs = uint32(b);
                    }
                }
            }
            return new Gateway(nextHop, srcAs, dstAs);
        }
    }

    private ExtendedData() {
    }

    /** sFlow address: uint32 type discriminator, then 4 (IPv4) or 16 (IPv6) octets. */
    static InetAddress address(final ByteBuf b) {
        if (b.readableBytes() < 4) {
            return null;
        }
        final long type = uint32(b);
        final int size = type == 1 ? 4 : type == 2 ? 16 : -1;
        if (size < 0 || b.readableBytes() < size) {
            return null;
        }
        return inetAddress(b, size);
    }
}
