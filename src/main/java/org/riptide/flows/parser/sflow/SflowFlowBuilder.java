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
 *
 * <p>The rate in those two products is the <em>guarded</em> one. A wire rate that is not finite or
 * is below {@code 1} scales by {@code 1} and reports provenance {@code Assumed}, because a rate of
 * {@code 0} would otherwise persist and collide with the value the rollups reserve for rows
 * aggregated before the rate was carried (#470).</p>
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

            // Counters are scaled by the same rate the flow reports, and by the same guarded value.
            // Scaling by the raw wire rate while reporting a guarded one would produce a row that
            // reads as real unsampled traffic of zero volume — a rate of 0 gives bytes = 0 and
            // packets = 0, with nothing marking it as junk.
            @Override
            public long getBytes() {
                return sample.frameLength() != null ? sample.frameLength() * scale() : 0;
            }

            @Override
            public long getPackets() {
                return scale();
            }

            /** The rate the counters are scaled by: the exporter's, or 1 when it stated nothing usable. */
            private long scale() {
                return usable(sample.samplingRate) ? sample.samplingRate : 1L;
            }

            @Override
            public Direction getDirection() {
                return Direction.UNKNOWN;
            }

            @Override
            public int getEngineId() {
                // sub_agent_id is a full uint32; clamp instead of casting negative —
                // the persisted engineId column rejects out-of-range values batch-wide
                return (int) Math.min(datagram.subAgentId, Integer.MAX_VALUE);
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
                return usable(sample.samplingRate) ? sample.samplingRate : 1.0d;
            }

            /*
             * Always on the sample: sFlow carries the rate by construction, so there is no ladder
             * here and no rung below this one.
             *
             * <p>Note what this provenance does <em>not</em> say. sFlow counters are already
             * scaled at ingest ({@code bytes = frameLength × samplingRate}), so multiplying an
             * sFlow row by its interval double-counts it. That is a property of the protocol, not
             * of the rung, and {@code flowProtocol} is what distinguishes it.
             */
            @Override
            public SamplingProvenance getSamplingProvenance() {
                return usable(sample.samplingRate) ? SamplingProvenance.Record : SamplingProvenance.Assumed;
            }
        };
    }

    /**
     * Whether a rate off the wire is one an exporter could have meant.
     *
     * <p>The same rule the NetFlow and IPFIX builders apply, and it belongs here too even though
     * sFlow carries the rate by construction: {@code samplingRate} is a uint32 read straight from
     * the sample, and nothing on the wire stops an agent sending {@code 0}.</p>
     *
     * <p><b>Zero is reserved, and not only here.</b> A rollup that has gained the rate as a
     * dimension gives rows aggregated before the append the column's type default — {@code 0} — and
     * that value is the only thing marking them, since a column joining the sorting key cannot
     * carry an explicit {@code DEFAULT}. A live flow persisting {@code 0} would make
     * {@code WHERE samplingInterval > 0} silently drop real traffic and stop {@code = 0} meaning
     * what the schema says it means (#470). An unusable rate therefore reads as {@code 1.0} and
     * says so, dropping to the bottom rung rather than inventing a rate.</p>
     */
    private static boolean usable(final double interval) {
        return Double.isFinite(interval) && interval >= 1.0d;
    }
}
