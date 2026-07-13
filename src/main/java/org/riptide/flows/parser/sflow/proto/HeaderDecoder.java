/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser.sflow.proto;

import io.netty.buffer.ByteBuf;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.riptide.flows.utils.BufferUtils.bytes;
import static org.riptide.flows.utils.BufferUtils.uint16;
import static org.riptide.flows.utils.BufferUtils.uint32;
import static org.riptide.flows.utils.BufferUtils.uint8;

/**
 * Decodes a sampled raw packet header: Ethernet (with 802.1Q/QinQ tags) → IPv4/IPv6 →
 * TCP/UDP. Truncation-tolerant at every layer — the sampler cuts headers (typically at
 * 128 bytes), so running out of bytes is normal and yields whatever was decoded so far.
 * Undecodable payloads (ARP, MPLS, …) yield an empty {@link PacketInfo}: the flow
 * degrades to sample-level data, mirroring the enrichment ladder's floor.
 */
public final class HeaderDecoder {

    /** {@code header_protocol} values (sflow_version_5.txt §5.2.4). */
    public static final int PROTO_ETHERNET = 1;
    public static final int PROTO_IPV4 = 11;
    public static final int PROTO_IPV6 = 12;

    private static final int ETHERTYPE_VLAN = 0x8100;
    private static final int ETHERTYPE_QINQ = 0x88A8;
    private static final int ETHERTYPE_IPV4 = 0x0800;
    private static final int ETHERTYPE_IPV6 = 0x86DD;

    private static final int IP_PROTO_TCP = 6;
    private static final int IP_PROTO_UDP = 17;

    /** Chained IPv6 extension headers we walk through; bounded to defuse crafted loops. */
    private static final int MAX_IPV6_EXTENSIONS = 8;

    private HeaderDecoder() {
    }

    public static PacketInfo decode(final int headerProtocol, final ByteBuf header) {
        final PacketInfo info = new PacketInfo();
        switch (headerProtocol) {
            case PROTO_ETHERNET -> ethernet(header, info);
            case PROTO_IPV4 -> ipv4(header, info);
            case PROTO_IPV6 -> ipv6(header, info);
            default -> {
                // unknown link type: floor flow
            }
        }
        return info;
    }

    private static void ethernet(final ByteBuf b, final PacketInfo info) {
        if (b.readableBytes() < 14) {
            return;
        }
        b.skipBytes(12); // dst + src MAC
        int etherType = uint16(b);
        while ((etherType == ETHERTYPE_VLAN || etherType == ETHERTYPE_QINQ) && b.readableBytes() >= 4) {
            final int tag = uint16(b);
            if (info.vlan == null) { // outer tag wins, matching device-reported VLANs
                info.vlan = tag & 0x0FFF;
            }
            etherType = uint16(b);
        }
        if (etherType == ETHERTYPE_IPV4) {
            ipv4(b, info);
        } else if (etherType == ETHERTYPE_IPV6) {
            ipv6(b, info);
        }
        // anything else (ARP, MPLS, LLDP, …): keep what we have
    }

    private static void ipv4(final ByteBuf b, final PacketInfo info) {
        if (b.readableBytes() < 20) {
            return;
        }
        final int versionAndIhl = uint8(b);
        if ((versionAndIhl >>> 4) != 4) {
            return;
        }
        final int headerLength = (versionAndIhl & 0x0F) * 4;
        info.ipVersion = 4;
        info.tos = uint8(b);
        b.skipBytes(4); // total length + identification
        final int flagsAndFragment = uint16(b);
        b.skipBytes(1); // ttl
        info.protocol = uint8(b);
        b.skipBytes(2); // checksum
        info.srcAddr = address(bytes(b, 4));
        info.dstAddr = address(bytes(b, 4));

        final int options = headerLength - 20;
        if (options < 0 || b.readableBytes() < options) {
            return;
        }
        b.skipBytes(options);

        if ((flagsAndFragment & 0x1FFF) != 0) {
            return; // non-first fragment: no L4 header present
        }
        transport(b, info);
    }

    private static void ipv6(final ByteBuf b, final PacketInfo info) {
        if (b.readableBytes() < 40) {
            return;
        }
        final long first = uint32(b);
        if ((first >>> 28) != 6) {
            return;
        }
        info.ipVersion = 6;
        info.tos = (int) ((first >>> 20) & 0xFF); // traffic class
        b.skipBytes(2); // payload length
        int next = uint8(b);
        b.skipBytes(1); // hop limit
        info.srcAddr = address(bytes(b, 16));
        info.dstAddr = address(bytes(b, 16));

        for (int i = 0; i < MAX_IPV6_EXTENSIONS; i++) {
            if (next == 0 || next == 43 || next == 60) { // hop-by-hop, routing, dest-opts
                if (b.readableBytes() < 2) {
                    return;
                }
                next = uint8(b);
                final int length = (uint8(b) + 1) * 8 - 2;
                if (b.readableBytes() < length) {
                    return;
                }
                b.skipBytes(length);
            } else if (next == 44) { // fragment
                if (b.readableBytes() < 8) {
                    return;
                }
                next = uint8(b);
                b.skipBytes(1);
                final int fragmentOffset = uint16(b) >>> 3;
                b.skipBytes(4); // identification
                if (fragmentOffset != 0) {
                    return; // non-first fragment: no L4 header present
                }
            } else {
                break;
            }
        }
        info.protocol = next;
        transport(b, info);
    }

    private static void transport(final ByteBuf b, final PacketInfo info) {
        if (info.protocol != null && (info.protocol == IP_PROTO_TCP || info.protocol == IP_PROTO_UDP)) {
            if (b.readableBytes() < 4) {
                return;
            }
            info.srcPort = uint16(b);
            info.dstPort = uint16(b);
            if (info.protocol == IP_PROTO_TCP && b.readableBytes() >= 10) {
                b.skipBytes(8); // sequence + acknowledgement
                b.skipBytes(1); // data offset
                info.tcpFlags = uint8(b);
            }
        }
    }

    private static InetAddress address(final byte[] octets) {
        try {
            return InetAddress.getByAddress(octets);
        } catch (final UnknownHostException e) {
            // unreachable: length is always 4 or 16
            throw new IllegalStateException(e);
        }
    }
}
