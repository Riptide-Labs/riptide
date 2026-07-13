/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser.sflow;

import org.riptide.flows.parser.data.Flow;
import org.riptide.flows.parser.sflow.proto.Datagram;
import org.riptide.flows.parser.sflow.proto.FlowSample;
import org.riptide.flows.parser.sflow.proto.PacketInfo;

import java.net.InetAddress;
import java.time.Instant;

/**
 * Maps one sFlow flow sample onto the {@link Flow} contract. Samples are point events,
 * not cache records: first/last switched collapse to the receive time, and volume is
 * the statistical estimate {@code frame_length × sampling_rate} /
 * {@code packets = sampling_rate}. Missing decode results leave packet-level fields at
 * their floor values — the flow is still emitted.
 */
public final class SflowFlowBuilder {

    private SflowFlowBuilder() {
    }

    public static Flow buildFlow(final Instant receivedAt,
                                 final Datagram datagram,
                                 final FlowSample sample) {
        final PacketInfo packet = sample.packet() != null ? sample.packet() : new PacketInfo();

        return new Flow() {
            @Override
            public Instant getReceivedAt() {
                return receivedAt;
            }

            @Override
            public Instant getTimestamp() {
                return receivedAt;
            }

            @Override
            public FlowProtocol getFlowProtocol() {
                return FlowProtocol.SFLOW;
            }

            @Override
            public int getFlowRecords() {
                return datagram.samples.size();
            }

            @Override
            public long getFlowSeqNum() {
                return sample.sequence;
            }

            @Override
            public Instant getFirstSwitched() {
                return receivedAt;
            }

            @Override
            public Instant getLastSwitched() {
                return receivedAt;
            }

            @Override
            public int getInputSnmp() {
                return sample.input.ifIndex();
            }

            @Override
            public int getOutputSnmp() {
                return sample.output.ifIndex();
            }

            @Override
            public long getSrcAs() {
                return sample.extendedGateway() != null ? sample.extendedGateway().srcAs() : 0;
            }

            @Override
            public InetAddress getSrcAddr() {
                return packet.srcAddr;
            }

            @Override
            public int getSrcMaskLen() {
                return sample.extendedRouter() != null ? sample.extendedRouter().srcMaskLen() : 0;
            }

            @Override
            public int getSrcPort() {
                return packet.srcPort != null ? packet.srcPort : 0;
            }

            @Override
            public long getDstAs() {
                return sample.extendedGateway() != null ? sample.extendedGateway().dstAs() : 0;
            }

            @Override
            public InetAddress getDstAddr() {
                return packet.dstAddr;
            }

            @Override
            public int getDstMaskLen() {
                return sample.extendedRouter() != null ? sample.extendedRouter().dstMaskLen() : 0;
            }

            @Override
            public int getDstPort() {
                return packet.dstPort != null ? packet.dstPort : 0;
            }

            @Override
            public InetAddress getNextHop() {
                if (sample.extendedRouter() != null) {
                    return sample.extendedRouter().nextHop();
                }
                return sample.extendedGateway() != null ? sample.extendedGateway().nextHop() : null;
            }

            @Override
            public long getBytes() {
                return sample.frameLength() != null ? sample.frameLength() * sample.samplingRate : 0;
            }

            @Override
            public long getPackets() {
                return sample.samplingRate;
            }

            @Override
            public Direction getDirection() {
                return Direction.UNKNOWN;
            }

            @Override
            public int getEngineId() {
                return (int) datagram.subAgentId;
            }

            @Override
            public int getEngineType() {
                return 0;
            }

            @Override
            public int getVlan() {
                if (sample.extendedSwitch() != null) {
                    return sample.extendedSwitch().srcVlan();
                }
                return packet.vlan != null ? packet.vlan : 0;
            }

            @Override
            public int getIpProtocolVersion() {
                return packet.ipVersion != null ? packet.ipVersion : 0;
            }

            @Override
            public int getProtocol() {
                return packet.protocol != null ? packet.protocol : 0;
            }

            @Override
            public int getTcpFlags() {
                return packet.tcpFlags != null ? packet.tcpFlags : 0;
            }

            @Override
            public int getTos() {
                return packet.tos != null ? packet.tos : 0;
            }

            @Override
            public SamplingAlgorithm getSamplingAlgorithm() {
                return SamplingAlgorithm.RandomNOutOfNSampling;
            }

            @Override
            public double getSamplingInterval() {
                return sample.samplingRate;
            }
        };
    }
}
