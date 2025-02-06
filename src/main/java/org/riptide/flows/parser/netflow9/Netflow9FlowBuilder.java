package org.riptide.flows.parser.netflow9;

import lombok.Getter;
import lombok.Setter;
import org.riptide.flows.parser.ValueConversionService;
import org.riptide.flows.parser.data.Flow;
import org.riptide.flows.parser.data.Optionals;
import org.riptide.flows.parser.data.Timeout;
import org.riptide.flows.parser.netflow9.proto.Packet;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.stream.Stream;

public class Netflow9FlowBuilder {

    private final ValueConversionService conversionService;

    @Getter
    @Setter
    private Duration flowActiveTimeoutFallback;
    @Getter
    @Setter
    private Duration flowInactiveTimeoutFallback;
    @Getter
    @Setter
    private Long flowSamplingIntervalFallback;

    public Netflow9FlowBuilder(ValueConversionService conversionService) {
        this.conversionService = Objects.requireNonNull(conversionService);
    }

    public Stream<Flow> buildFlows(final Instant receivedAt, final Packet packet) {
        return createRawFlows(packet)
                .map(rawFlow -> buildFlow(receivedAt, rawFlow));
    }

    public Flow buildFlow(final Instant receivedAt,
                          final Netflow9RawFlow raw) {
        final var bootTime = raw.unixSecs.minus(raw.sysUpTime);
        final var firstSwitched = Optionals.of(raw.FIRST_SWITCHED).map(bootTime::plus).orElse(raw.flowStartMilliseconds);
        final var lastSwitched = Optionals.of(raw.LAST_SWITCHED).map(bootTime::plus).orElse(raw.flowEndMilliseconds);
        final var activeTimeout = Optionals.first(raw.FLOW_ACTIVE_TIMEOUT, flowActiveTimeoutFallback).orElse(null);
        final var inactiveTimeout = Optionals.first(raw.FLOW_INACTIVE_TIMEOUT, flowInactiveTimeoutFallback).orElse(null);
        final var bytes = Optionals.of(raw.IN_BYTES).orElse(0L);
        final var packets = Optionals.of(raw.IN_PKTS).orElse(0L);
        final var deltaSwitched = new Timeout()
                .withActiveTimeout(activeTimeout)
                .withInactiveTimeout(inactiveTimeout)
                .withFirstSwitched(firstSwitched)
                .withLastSwitched(lastSwitched)
                .withNumBytes(bytes)
                .withNumPackets(packets)
                .calculateDeltaSwitched();

        return Flow.builder()
                .receivedAt(receivedAt)

                .timestamp(raw.unixSecs)

                .flowProtocol(Flow.FlowProtocol.NetflowV9)
                .flowRecords(raw.recordCount)
                .flowSeqNum(raw.sequenceNumber)

                .firstSwitched(firstSwitched)
                .deltaSwitched(deltaSwitched)
                .lastSwitched(lastSwitched)

                .inputSnmp(Optionals.first(raw.ingressPhysicalInterface, raw.INPUT_SNMP).orElse(0))
                .outputSnmp(Optionals.first(raw.egressPhysicalInterface, raw.OUTPUT_SNMP).orElse(0))

                .srcAs(Optionals.of(raw.SRC_AS).orElse(0L))
                .srcAddr(Optionals.first(raw.IPV6_SRC_ADDR, raw.IPV4_SRC_ADDR).orElse(null))
                .srcMaskLen(Optionals.first(raw.IPV6_SRC_MASK, raw.SRC_MASK).orElse(0))
                .srcPort(Optionals.of(raw.L4_SRC_PORT).orElse(0))

                .dstAs(Optionals.of(raw.DST_AS).orElse(0L))
                .dstAddr(Optionals.first(raw.IPV6_DST_ADDR, raw.IPV4_DST_ADDR).orElse(null))
                .dstMaskLen(Optionals.first(raw.IPV6_DST_MASK, raw.DST_MASK).orElse(0))
                .dstPort(Optionals.of(raw.L4_DST_PORT).orElse(0))

                .nextHop(Optionals.first(raw.IPV6_NEXT_HOP,
                                raw.IPV4_NEXT_HOP,
                                raw.BPG_IPV6_NEXT_HOP,
                                raw.BPG_IPV4_NEXT_HOP)
                        .orElse(null))

                .bytes(bytes)
                .packets(packets)

                .direction(
                        switch (raw.DIRECTION) {
                            case 0 -> Flow.Direction.INGRESS;
                            case 1 -> Flow.Direction.EGRESS;
                            case null, default -> Flow.Direction.UNKNOWN;
                        })

                .engineId(Optionals.of(raw.ENGINE_ID).orElse(0))
                .engineType(Optionals.of(raw.ENGINE_TYPE).orElse(0))

                .vlan(Optionals.first(raw.SRC_VLAN, raw.DST_VLAN).orElse(0))
                .ipProtocolVersion(Optionals.of(raw.IP_PROTOCOL_VERSION).orElse(0))
                .protocol(Optionals.of(raw.PROTOCOL).orElse(0))
                .tcpFlags(Optionals.of(raw.TCP_FLAGS).orElse(0))
                .tos(Optionals.of(raw.TOS).orElse(0))

                .samplingAlgorithm(
                        switch (raw.SAMPLING_ALGORITHM) {
                            case 1 -> Flow.SamplingAlgorithm.SystematicCountBasedSampling;
                            case 2 -> Flow.SamplingAlgorithm.RandomNOutOfNSampling;
                            case null, default -> Flow.SamplingAlgorithm.Unassigned;
                        })
                .samplingInterval(Optionals.of(raw.SAMPLING_INTERVAL).orElse(1.0))

                .build();
    }

    private Stream<Netflow9RawFlow> createRawFlows(Packet packet) {
        return packet.dataSets.stream()
                .flatMap(ds -> ds.records.stream())
                .map(record -> {
                    final var dummyFlow = new Netflow9RawFlow();
                    for (var value : record.getValues()) {
                        this.conversionService.convert(value, dummyFlow);
                    }
                    dummyFlow.recordCount = packet.header.count;
                    dummyFlow.sysUpTime = Duration.ofMillis(packet.header.sysUpTime);
                    dummyFlow.unixSecs = Instant.ofEpochSecond(packet.header.unixSecs);
                    dummyFlow.sequenceNumber = packet.header.sequenceNumber;
                    return dummyFlow;
                });
    }
}
