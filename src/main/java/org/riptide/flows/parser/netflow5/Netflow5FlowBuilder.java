/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser.netflow5;


import org.riptide.flows.parser.data.Flow;
import org.riptide.flows.parser.netflow5.proto.Header;
import org.riptide.flows.parser.netflow5.proto.Packet;
import org.riptide.flows.parser.netflow5.proto.Record;

import java.net.InetAddress;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.stream.Stream;

public final class Netflow5FlowBuilder {

    /**
     * The rate an operator declared for this receiver, {@code null} when none is configured.
     *
     * <p>NetFlow v5 has no options-template mechanism, so unlike v9 there is no rung between the
     * exporter and this one: whatever the exporter cannot say, only configuration can.
     */
    private Long flowSamplingIntervalFallback;

    public void setFlowSamplingIntervalFallback(final Long flowSamplingIntervalFallback) {
        this.flowSamplingIntervalFallback = flowSamplingIntervalFallback;
    }

    public Stream<Flow> buildFlows(final Instant receivedAt, final Packet packet) {
        return packet.records.stream()
                .map(record -> buildFlow(receivedAt, packet.header, record));
    }

    public Flow buildFlow(final Instant receivedAt,
                          final Header header,
                          final Record record) {

        final var timestamp = Instant.ofEpochSecond(header.unixSecs, header.unixNSecs);
        final var bootTime = timestamp.minus(header.sysUptime, ChronoUnit.MILLIS);

        return new Flow() {
            @Override
            public Instant getReceivedAt() {
                return receivedAt;
            }

            @Override
            public Instant getTimestamp() {
                return timestamp;
            }

            @Override
            public Flow.FlowProtocol getFlowProtocol() {
                return Flow.FlowProtocol.NetflowV5;
            }

            @Override
            public int getFlowRecords() {
                return header.count;
            }

            @Override
            public long getFlowSeqNum() {
                return header.flowSequence;
            }

            @Override
            public Instant getFirstSwitched() {
                return bootTime.plus(record.first, ChronoUnit.MILLIS);
            }

            @Override
            public Instant getLastSwitched() {
                return bootTime.plus(record.last, ChronoUnit.MILLIS);
            }

            @Override
            public int getInputSnmp() {
                return record.input;
            }

            @Override
            public int getOutputSnmp() {
                return record.output;
            }

            @Override
            public long getSrcAs() {
                return record.srcAs;
            }

            @Override
            public InetAddress getSrcAddr() {
                return record.srcAddr;
            }

            @Override
            public int getSrcMaskLen() {
                return record.srcMask;
            }

            @Override
            public int getSrcPort() {
                return record.srcPort;
            }

            @Override
            public long getDstAs() {
                return record.dstAs;
            }

            @Override
            public InetAddress getDstAddr() {
                return record.dstAddr;
            }

            @Override
            public int getDstMaskLen() {
                return record.dstMask;
            }

            @Override
            public int getDstPort() {
                return record.dstPort;
            }

            @Override
            public InetAddress getNextHop() {
                return record.nextHop;
            }

            @Override
            public long getBytes() {
                return record.dOctets;
            }

            @Override
            public long getPackets() {
                return record.dPkts;
            }

            @Override
            public Flow.Direction getDirection() {
                return record.egress
                        ? Flow.Direction.EGRESS
                        : Flow.Direction.INGRESS;
            }

            @Override
            public int getEngineId() {
                return header.engineId;
            }

            @Override
            public int getEngineType() {
                return header.engineType;
            }

            @Override
            public int getVlan() {
                return 0;
            }

            @Override
            public int getIpProtocolVersion() {
                return 4;
            }

            @Override
            public int getProtocol() {
                return record.proto;
            }

            @Override
            public int getTcpFlags() {
                return record.tcpFlags;
            }

            @Override
            public int getTos() {
                return record.tos;
            }

            @Override
            public Flow.SamplingAlgorithm getSamplingAlgorithm() {
                return switch (header.samplingAlgorithm) {
                    case 1 -> Flow.SamplingAlgorithm.SystematicCountBasedSampling;
                    case 2 -> Flow.SamplingAlgorithm.RandomNOutOfNSampling;
                    default -> Flow.SamplingAlgorithm.Unassigned;
                };
            }

            /**
             * What the operator configured, then unsampled. NetFlow v5 exporters cannot advertise
             * a rate out of band the way v9 does, so this receiver-wide setting is the only rung
             * above the default — until the packet header is read as well.
             */
            @Override
            public double getSamplingInterval() {
                final Double configured = usable(asDouble(flowSamplingIntervalFallback));
                return configured != null ? configured : 1.0;
            }
        };
    }

    /**
     * A rate counts as an answer when it is present and finite, including an explicit 1: an
     * operator configuring 1 has stated this receiver's exporters do not sample. Only absent,
     * 0 and non-finite fall through to the next rung. Mirrors {@code Netflow9FlowBuilder}.
     */
    private static Double usable(final Double interval) {
        return interval != null && Double.isFinite(interval) && interval >= 1.0 ? interval : null;
    }

    private static Double asDouble(final Long value) {
        return value != null ? value.doubleValue() : null;
    }
}
