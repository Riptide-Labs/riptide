package org.riptide.flows.parser.netflow5;


import org.riptide.flows.parser.data.Flow;
import org.riptide.flows.parser.netflow5.proto.Header;
import org.riptide.flows.parser.netflow5.proto.Record;

import java.net.InetAddress;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

public final class Netflow5FlowBuilder {
    private Netflow5FlowBuilder() {

    }

    public static Flow buildFlow(final Instant receivedAt,
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

            @Override
            public double getSamplingInterval() {
                return 1.0;
            }
        };
    }
}
