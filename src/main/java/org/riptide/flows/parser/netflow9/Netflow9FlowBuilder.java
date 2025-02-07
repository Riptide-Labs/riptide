package org.riptide.flows.parser.netflow9;

import lombok.Getter;
import lombok.Setter;
import org.riptide.flows.parser.ValueConversionService;
import org.riptide.flows.parser.data.Flow;
import org.riptide.flows.parser.data.Optionals;
import org.riptide.flows.parser.data.Timeout;
import org.riptide.flows.parser.netflow9.proto.Packet;

import java.net.InetAddress;
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

    public Netflow9FlowBuilder(final ValueConversionService conversionService) {
        this.conversionService = Objects.requireNonNull(conversionService);
    }

    public Stream<Flow> buildFlows(final Instant receivedAt,
                                   final Packet packet) {
        return createRawFlows(packet)
                .map(rawFlow -> buildFlow(receivedAt, rawFlow));
    }

    public Flow buildFlow(final Instant receivedAt,
                          final Netflow9RawFlow raw) {
        final var bootTime = raw.unixSecs.minus(raw.sysUpTime);

        return new Flow() {
            private Instant getBootTime() {
                return bootTime;
            }

            @Override
            public Instant getReceivedAt() {
                return receivedAt;
            }

            @Override
            public Instant getTimestamp() {
                return raw.unixSecs;
            }

            @Override
            public Flow.FlowProtocol getFlowProtocol() {
                return Flow.FlowProtocol.NetflowV9;
            }

            @Override
            public int getFlowRecords() {
                return raw.recordCount;
            }

            @Override
            public long getFlowSeqNum() {
                return raw.sequenceNumber;
            }

            @Override
            public Instant getFirstSwitched() {
                return Optionals.of(raw.FIRST_SWITCHED)
                        .map(this.getBootTime()::plus)
                        .orElse(raw.flowStartMilliseconds);
            }

            @Override
            public Instant getDeltaSwitched() {
                final var activeTimeout = Optionals.first(raw.FLOW_ACTIVE_TIMEOUT, flowActiveTimeoutFallback).orElse(null);
                final var inactiveTimeout = Optionals.first(raw.FLOW_INACTIVE_TIMEOUT, flowInactiveTimeoutFallback).orElse(null);

                return new Timeout()
                        .withActiveTimeout(activeTimeout)
                        .withInactiveTimeout(inactiveTimeout)
                        .withFirstSwitched(this.getFirstSwitched())
                        .withLastSwitched(this.getLastSwitched())
                        .withNumBytes(this.getBytes())
                        .withNumPackets(this.getPackets())
                        .calculateDeltaSwitched();
            }

            @Override
            public Instant getLastSwitched() {
                return Optionals.of(raw.LAST_SWITCHED)
                        .map(this.getBootTime()::plus)
                        .orElse(raw.flowEndMilliseconds);
            }

            @Override
            public int getInputSnmp() {
                return Optionals.first(raw.ingressPhysicalInterface, raw.INPUT_SNMP).orElse(0);
            }

            @Override
            public int getOutputSnmp() {
                return Optionals.first(raw.egressPhysicalInterface, raw.OUTPUT_SNMP).orElse(0);
            }

            @Override
            public long getSrcAs() {
                return Optionals.of(raw.SRC_AS).orElse(0L);
            }

            @Override
            public InetAddress getSrcAddr() {
                return Optionals.first(raw.IPV6_SRC_ADDR, raw.IPV4_SRC_ADDR).orElse(null);
            }

            @Override
            public int getSrcMaskLen() {
                return Optionals.first(raw.IPV6_SRC_MASK, raw.SRC_MASK).orElse(0);
            }

            @Override
            public int getSrcPort() {
                return Optionals.of(raw.L4_SRC_PORT).orElse(0);
            }

            @Override
            public long getDstAs() {
                return Optionals.of(raw.DST_AS).orElse(0L);
            }

            @Override
            public InetAddress getDstAddr() {
                return Optionals.first(raw.IPV6_DST_ADDR, raw.IPV4_DST_ADDR).orElse(null);
            }

            @Override
            public int getDstMaskLen() {
                return Optionals.first(raw.IPV6_DST_MASK, raw.DST_MASK).orElse(0);
            }

            @Override
            public int getDstPort() {
                return Optionals.of(raw.L4_DST_PORT).orElse(0);
            }

            @Override
            public InetAddress getNextHop() {
                return Optionals.first(raw.IPV6_NEXT_HOP, raw.IPV4_NEXT_HOP, raw.BPG_IPV6_NEXT_HOP, raw.BPG_IPV4_NEXT_HOP).orElse(null);
            }

            @Override
            public long getBytes() {
                return Optionals.of(raw.IN_BYTES).orElse(0L);
            }

            @Override
            public long getPackets() {
                return Optionals.of(raw.IN_PKTS).orElse(0L);
            }

            @Override
            public Flow.Direction getDirection() {
                return switch (raw.DIRECTION) {
                    case 0 -> Flow.Direction.INGRESS;
                    case 1 -> Flow.Direction.EGRESS;
                    case null, default -> Flow.Direction.UNKNOWN;
                };
            }

            @Override
            public int getEngineId() {
                return Optionals.of(raw.ENGINE_ID).orElse(0);
            }

            @Override
            public int getEngineType() {
                return Optionals.of(raw.ENGINE_TYPE).orElse(0);
            }

            @Override
            public int getVlan() {
                return Optionals.first(raw.SRC_VLAN, raw.DST_VLAN).orElse(0);
            }

            @Override
            public int getIpProtocolVersion() {
                return Optionals.of(raw.IP_PROTOCOL_VERSION).orElse(0);
            }

            @Override
            public int getProtocol() {
                return Optionals.of(raw.PROTOCOL).orElse(0);
            }

            @Override
            public int getTcpFlags() {
                return Optionals.of(raw.TCP_FLAGS).orElse(0);
            }

            @Override
            public int getTos() {
                return Optionals.of(raw.TOS).orElse(0);
            }

            @Override
            public Flow.SamplingAlgorithm getSamplingAlgorithm() {
                return switch (raw.SAMPLING_ALGORITHM) {
                    case 1 -> Flow.SamplingAlgorithm.SystematicCountBasedSampling;
                    case 2 -> Flow.SamplingAlgorithm.RandomNOutOfNSampling;
                    case null, default -> Flow.SamplingAlgorithm.Unassigned;
                };
            }

            @Override
            public double getSamplingInterval() {
                return Optionals.of(raw.SAMPLING_INTERVAL).orElse(1.0);
            }
        };
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
