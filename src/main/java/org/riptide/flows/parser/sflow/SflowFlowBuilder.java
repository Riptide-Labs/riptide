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
            /*
             * The stated frame length, scaled by the stated rate — or nothing, if the frame length is
             * not one a frame could have.
             *
             * <p>Both values are {@code uint32} off the wire in a {@code long}, so their raw product
             * reaches 1.8e19 and wraps a signed 64-bit integer. The {@code bytes} column is
             * {@code UInt64}, so the wrapped negative reads back as that same 1.8e19 (#588). sFlow has
             * no transport authentication, so this is attacker-controlled on any bound interface.</p>
             *
             * <p><b>Refused, not clamped.</b> Clamping an absurd frame length to the bound and scaling
             * it anyway still yields 5.6e14 — five orders smaller than the wrap and just as capable of
             * swamping any aggregate containing it, while silently presenting a fabricated number as a
             * measurement. Refusing matches what this class already does with a rate it does not
             * believe: substitute a neutral value rather than invent one. A frame length past the bound
             * is not a measurement, so the sample contributes no bytes, exactly as one carrying no
             * frame length at all does.</p>
             *
             * <p>The <em>rate</em> is deliberately not bounded here. A rate the wire can carry is a rate
             * riptide records, which is what {@code sflowAcceptsTheLargestRateTheWireCanCarry} pins and
             * what #467 asks for. That leaves a hostile rate applied to a legitimate frame still able to
             * distort an aggregate — a real gap, and a wider one than this overflow.</p>
             */
            @Override
            public long getBytes() {
                final Long stated = sample.frameLength();
                if (stated == null || stated > MAX_FRAME_LENGTH) {
                    return 0;
                }
                final long scale = scale();
                // unreachable while the bound above holds, and the reason widening it cannot silently
                // reintroduce the wrap. Refuses rather than saturating, for the same reason as above:
                // Long.MAX_VALUE is no more a measurement than the wrapped value it would replace.
                if (stated != 0 && scale > Long.MAX_VALUE / stated) {
                    return 0;
                }
                return stated * scale;
            }

            @Override
            public long getPackets() {
                return scale();
            }

            /* The rate the counters are scaled by: the exporter's, or 1 when it stated nothing usable. */
            private long scale() {
                return usable((double) sample.samplingRate) ? sample.samplingRate : 1L;
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
                return usable((double) sample.samplingRate) ? (double) sample.samplingRate : 1.0d;
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
                return usable((double) sample.samplingRate) ? SamplingProvenance.Record : SamplingProvenance.Assumed;
            }
        };
    }

    /**
     * The largest frame length riptide will believe.
     *
     * <p>sFlow v5 defines {@code frame_length} as the length of the MAC packet received on the
     * network, so it includes the layer-2 header: a maximum-size IP datagram over Ethernet reports
     * around 65549, not 65535. This bound is deliberately well clear of that rather than exact,
     * because the cost of being slightly too tight is refusing a real sample, and no medium riptide
     * will meet carries a frame anywhere near it.</p>
     *
     * <p>It also keeps the product far short of wrapping: {@code 131072 * 4294967295} is 5.6e14,
     * some 16,000 times below {@code Long.MAX_VALUE}.</p>
     */
    private static final long MAX_FRAME_LENGTH = 131_072L;

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
