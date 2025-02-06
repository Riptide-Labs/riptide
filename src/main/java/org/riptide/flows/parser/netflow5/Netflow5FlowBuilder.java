package org.riptide.flows.parser.netflow5;


import org.riptide.flows.parser.data.Flow;
import org.riptide.flows.parser.netflow5.proto.Header;
import org.riptide.flows.parser.netflow5.proto.Record;

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

        return Flow.builder()
                .receivedAt(receivedAt)

                .timestamp(timestamp)

                .flowProtocol(Flow.FlowProtocol.NetflowV5)
                .flowRecords(header.count)
                .flowSeqNum(header.flowSequence)

                .firstSwitched(bootTime.plus(record.first, ChronoUnit.MILLIS))
                .lastSwitched(bootTime.plus(record.last, ChronoUnit.MILLIS))

                .inputSnmp(record.input)
                .outputSnmp(record.output)

                .srcAs(record.srcAs)
                .srcAddr(record.srcAddr)
                .srcMaskLen(record.srcMask)
                .srcPort(record.srcPort)

                .dstAs(record.dstAs)
                .dstAddr(record.dstAddr)
                .dstMaskLen(record.dstMask)
                .dstPort(record.dstPort)

                .nextHop(record.nextHop)

                .bytes(record.dOctets)
                .packets(record.dPkts)

                .direction(record.egress
                        ? Flow.Direction.EGRESS
                        : Flow.Direction.INGRESS)

                .engineId(header.engineId)
                .engineType(header.engineType)

                .vlan(0)
                .ipProtocolVersion(4)
                .protocol(record.proto)
                .tcpFlags(record.tcpFlags)
                .tos(record.tos)

                .samplingAlgorithm(
                        switch (header.samplingAlgorithm) {
                            case 1 -> Flow.SamplingAlgorithm.SystematicCountBasedSampling;
                            case 2 -> Flow.SamplingAlgorithm.RandomNOutOfNSampling;
                            default -> Flow.SamplingAlgorithm.Unassigned;
                        })
                .samplingInterval(header.samplingInterval)

                .build();
    }
}
