/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser.sflow.proto;

import com.google.common.base.MoreObjects;
import io.netty.buffer.ByteBuf;
import org.riptide.flows.parser.exceptions.InvalidPacketException;

import static org.riptide.flows.utils.BufferUtils.inetAddress;
import static org.riptide.flows.utils.BufferUtils.slice;
import static org.riptide.flows.utils.BufferUtils.uint32;

/**
 * One {@code flow_sample} (format 1) or {@code flow_sample_expanded} (format 3),
 * sflow_version_5.txt §5.3. Flow records the builder doesn't consume are skipped by
 * their XDR length; malformed extended records degrade to {@code null} fields.
 */
public final class FlowSample {

    /** Standard-enterprise flow-record formats we consume. */
    private static final int RECORD_SAMPLED_HEADER = 1;
    private static final int RECORD_SAMPLED_IPV4 = 3;
    private static final int RECORD_SAMPLED_IPV6 = 4;
    private static final int RECORD_EXTENDED_SWITCH = 1001;
    private static final int RECORD_EXTENDED_ROUTER = 1002;
    private static final int RECORD_EXTENDED_GATEWAY = 1003;

    public final long sequence;
    public final long samplingRate;
    public final long samplePool;
    public final long drops;
    public final InterfaceValue input;
    public final InterfaceValue output;

    private PacketInfo packet;
    private Long frameLength;

    private ExtendedData.Switch extendedSwitch;
    private ExtendedData.Router extendedRouter;
    private ExtendedData.Gateway extendedGateway;

    /** Decoded sampled packet, when a header/struct record was present. */
    public PacketInfo packet() {
        return this.packet;
    }

    /** Original on-the-wire frame length, when a header/struct record was present. */
    public Long frameLength() {
        return this.frameLength;
    }

    public ExtendedData.Switch extendedSwitch() {
        return this.extendedSwitch;
    }

    public ExtendedData.Router extendedRouter() {
        return this.extendedRouter;
    }

    public ExtendedData.Gateway extendedGateway() {
        return this.extendedGateway;
    }

    public FlowSample(final ByteBuf buffer, final boolean expanded) throws InvalidPacketException {
        if (buffer.readableBytes() < (expanded ? 44 : 32)) {
            throw new InvalidPacketException(buffer, "Truncated flow sample header");
        }
        this.sequence = uint32(buffer);
        if (expanded) {
            uint32(buffer); // source_id type
            uint32(buffer); // source_id index
        } else {
            uint32(buffer); // source_id (type << 24 | index)
        }
        this.samplingRate = uint32(buffer);
        this.samplePool = uint32(buffer);
        this.drops = uint32(buffer);
        if (expanded) {
            this.input = InterfaceValue.expanded(uint32(buffer), uint32(buffer));
            this.output = InterfaceValue.expanded(uint32(buffer), uint32(buffer));
        } else {
            this.input = InterfaceValue.compact(uint32(buffer));
            this.output = InterfaceValue.compact(uint32(buffer));
        }

        Xdr.walk(buffer, uint32(buffer), "flow record", this::record);
    }

    private void record(final int format, final ByteBuf b) {
        switch (format) {
            case RECORD_SAMPLED_HEADER -> {
                if (b.readableBytes() < 16) {
                    return;
                }
                final int headerProtocol = (int) uint32(b);
                this.frameLength = uint32(b);
                uint32(b); // stripped octets
                // min in long domain: header_length is uint32 and must not go negative
                // through an int cast (a crafted value would turn slice() into a throw)
                final long headerLength = uint32(b);
                this.packet = HeaderDecoder.decode(headerProtocol,
                        slice(b, (int) Math.min(headerLength, b.readableBytes())));
            }
            case RECORD_SAMPLED_IPV4 -> this.sampledIp(b, 4);
            case RECORD_SAMPLED_IPV6 -> this.sampledIp(b, 6);
            case RECORD_EXTENDED_SWITCH -> {
                this.extendedSwitch = ExtendedData.Switch.parse(b);
            }
            case RECORD_EXTENDED_ROUTER -> {
                this.extendedRouter = ExtendedData.Router.parse(b);
            }
            case RECORD_EXTENDED_GATEWAY -> {
                this.extendedGateway = ExtendedData.Gateway.parse(b);
            }
            default -> {
                // unconsumed record type: skip by length
            }
        }
    }

    /** {@code sampled_ipv4}/{@code sampled_ipv6}: the header pre-parsed by the sampler. */
    private void sampledIp(final ByteBuf b, final int version) {
        final int addressSize = version == 4 ? 4 : 16;
        if (b.readableBytes() < 8 + 2 * addressSize + 16) {
            return;
        }
        final PacketInfo info = new PacketInfo();
        info.ipVersion = version;
        this.frameLength = uint32(b);
        info.protocol = (int) uint32(b);
        info.srcAddr = inetAddress(b, addressSize);
        info.dstAddr = inetAddress(b, addressSize);
        info.srcPort = (int) uint32(b);
        info.dstPort = (int) uint32(b);
        info.tcpFlags = (int) uint32(b);
        info.tos = (int) uint32(b); // priority for IPv6 — same slot
        this.packet = info;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .omitNullValues()
                .add("sequence", this.sequence)
                .add("samplingRate", this.samplingRate)
                .add("input", this.input)
                .add("output", this.output)
                .add("packet", this.packet)
                .add("frameLength", this.frameLength)
                .add("extendedSwitch", this.extendedSwitch)
                .add("extendedRouter", this.extendedRouter)
                .add("extendedGateway", this.extendedGateway)
                .toString();
    }
}
