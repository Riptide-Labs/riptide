package org.riptide.flows.parser.ipfix;

import com.google.common.primitives.UnsignedLong;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.riptide.flows.parser.ValueConversionService;
import org.riptide.flows.parser.data.Flow;
import org.riptide.flows.parser.data.Optionals;
import org.riptide.flows.parser.data.Timeout;
import org.riptide.flows.parser.ipfix.proto.Packet;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.stream.Stream;


@Slf4j
public class IpFixFlowBuilder {

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

    public IpFixFlowBuilder(final ValueConversionService conversionService) {
        this.conversionService = Objects.requireNonNull(conversionService);
    }

    public Stream<Flow> buildFlows(final Instant receivedAt,
                                   final Packet packet) {
        return createRawFlows(packet)
                .map(rawFlow -> buildFlow(receivedAt, rawFlow));
    }

    public Flow buildFlow(final Instant receivedAt,
                          final IpfixRawFlow rawFlow) {
        return new Flow() {
            @Override
            public Instant getReceivedAt() {
                return receivedAt;
            }

            @Override
            public Instant getTimestamp() {
                return rawFlow.exportTime;
            }

            @Override
            public long getBytes() {
                return Optionals.first(
                        rawFlow.octetDeltaCount,
                        rawFlow.postOctetDeltaCount,
                        rawFlow.layer2OctetDeltaCount,
                        rawFlow.postLayer2OctetDeltaCount,
                        rawFlow.transportOctetDeltaCount)
                        .orElse(0L);
            }

            @Override
            public Direction getDirection() {
                return switch (rawFlow.flowDirection) {
                    case 0 -> Direction.INGRESS;
                    case 1 -> Direction.EGRESS;
                    case null, default -> Direction.UNKNOWN;
                };
            }

            @Override
            public InetAddress getDstAddr() {
                return Optionals.first(rawFlow.destinationIPv6Address, rawFlow.destinationIPv4Address).orElse(null);
            }

            @Override
            public long getDstAs() {
                return Optionals.first(rawFlow.bgpDestinationAsNumber).orElse(0L);
            }

            @Override
            public int getDstMaskLen() {
                return Optionals.first(rawFlow.destinationIPv6PrefixLength, rawFlow.destinationIPv4PrefixLength).orElse(0);
            }

            @Override
            public int getDstPort() {
                return Optionals.first(rawFlow.destinationTransportPort).orElse(0);
            }

            @Override
            public int getEngineId() {
                return Optionals.first(rawFlow.engineId).orElse(0);
            }

            @Override
            public int getEngineType() {
                return Optionals.first(rawFlow.engineType).orElse(0);
            }

            @Override
            public Instant getFirstSwitched() {
                return Optionals.first(
                                rawFlow.flowStartSeconds,
                                rawFlow.flowStartMilliseconds,
                                rawFlow.flowStartMicroseconds,
                                rawFlow.flowStartNanoseconds)
                        .orElseGet(() -> {
                            if (rawFlow.flowStartDeltaMicroseconds != null) {
                                return rawFlow.exportTime.plus(rawFlow.flowStartDeltaMicroseconds);
                            }
                            if (rawFlow.flowStartSysUpTime != null) {
                                return rawFlow.systemInitTimeMilliseconds.plus(rawFlow.flowStartSysUpTime);
                            }
                            return null;
                        });
            }

            @Override
            public Instant getLastSwitched() {
                return Optionals.first(
                                rawFlow.flowEndSeconds,
                                rawFlow.flowEndMilliseconds,
                                rawFlow.flowEndMicroseconds,
                                rawFlow.flowEndNanoseconds)
                        .orElseGet(() -> {
                            if (rawFlow.flowEndDeltaMicroseconds != null) {
                                return rawFlow.exportTime.plus(rawFlow.flowEndDeltaMicroseconds);
                            }
                            if (rawFlow.flowEndSysUpTime != null) {
                                return rawFlow.systemInitTimeMilliseconds.plus(rawFlow.flowEndSysUpTime);
                            }
                            return null;
                        });
            }

            @Override
            public Instant getDeltaSwitched() {
                final var flowActiveTimeout = Optionals.first(rawFlow.flowActiveTimeout, flowActiveTimeoutFallback).orElse(null);
                final var flowInactiveTimeout = Optionals.first(rawFlow.flowInactiveTimeout, flowInactiveTimeoutFallback).orElse(null);

                return new Timeout()
                        .withActiveTimeout(flowActiveTimeout)
                        .withInactiveTimeout(flowInactiveTimeout)
                        .withFirstSwitched(this.getFirstSwitched())
                        .withLastSwitched(this.getLastSwitched())
                        .withNumBytes(this.getBytes())
                        .withNumPackets(this.getPackets())
                        .calculateDeltaSwitched();
            }

            @Override
            public int getFlowRecords() {
                return rawFlow.recordCount;
            }

            @Override
            public long getFlowSeqNum() {
                return rawFlow.sequenceNumber;
            }

            @Override
            public int getInputSnmp() {
                return Optionals.first(rawFlow.ingressPhysicalInterface, rawFlow.ingressInterface).orElse(0);
            }

            @Override
            public int getIpProtocolVersion() {
                return Optionals.first(rawFlow.ipVersion).orElse(0);
            }

            @Override
            public InetAddress getNextHop() {
                return Optionals.first(rawFlow.ipNextHopIPv6Address, rawFlow.ipNextHopIPv4Address, rawFlow.bgpNextHopIPv6Address, rawFlow.bgpNextHopIPv4Address).orElse(null);
            }

            @Override
            public int getOutputSnmp() {
                return Optionals.first(rawFlow.egressPhysicalInterface, rawFlow.egressInterface).orElse(0);
            }

            @Override
            public long getPackets() {
                return Optionals.first(rawFlow.packetDeltaCount, rawFlow.postPacketDeltaCount, rawFlow.transportPacketDeltaCount).orElse(0L);
            }

            @Override
            public int getProtocol() {
                return Optionals.first(rawFlow.protocolIdentifier).orElse(0);
            }

            @Override
            public SamplingAlgorithm getSamplingAlgorithm() {
                return Optionals.first(rawFlow.samplingAlgorithm, rawFlow.samplerMode)
                        .map(deprecatedSamplingAlgorithm -> {
                            if (deprecatedSamplingAlgorithm == 1) {
                                return SamplingAlgorithm.SystematicCountBasedSampling;
                            }
                            if (deprecatedSamplingAlgorithm == 2) {
                                return SamplingAlgorithm.RandomNOutOfNSampling;
                            }
                            return switch (rawFlow.selectorAlgorithm) {
                                case 0 -> SamplingAlgorithm.Unassigned;
                                case 1 -> SamplingAlgorithm.SystematicCountBasedSampling;
                                case 2 -> SamplingAlgorithm.SystematicTimeBasedSampling;
                                case 3 -> SamplingAlgorithm.RandomNOutOfNSampling;
                                case 4 -> SamplingAlgorithm.UniformProbabilisticSampling;
                                case 5 -> SamplingAlgorithm.PropertyMatchFiltering;
                                case 6, 7, 8 -> SamplingAlgorithm.HashBasedFiltering;
                                case 9 -> SamplingAlgorithm.FlowStateDependentIntermediateFlowSelectionProcess;
                                case null, default -> null;
                            };
                        }).orElse(SamplingAlgorithm.Unassigned);
            }

            @Override
            public double getSamplingInterval() {
                return Optionals.first(rawFlow.samplingInterval, rawFlow.samplerRandomInterval)
                        .orElseGet(() -> {
                            switch (rawFlow.selectorAlgorithm) {
                                case 0, 8, 9 -> {
                                    return Double.NaN;
                                }
                                case 1, 2 -> {
                                    final var interval = Optionals.first(rawFlow.samplingFlowInterval, rawFlow.flowSamplingTimeInterval).orElse(1.0);
                                    final var spacing = Optionals.first(rawFlow.samplingFlowSpacing, rawFlow.flowSamplingTimeSpacing).orElse(0.0);
                                    return interval + spacing / interval;
                                }
                                case 3 -> {
                                    final var size = Optionals.of(rawFlow.samplingSize).orElse(1.0);
                                    final var population = Optionals.of(rawFlow.samplingPopulation).orElse(1.0);
                                    return population / size;
                                }
                                case 4 -> {
                                    final var probability = Optionals.of(rawFlow.samplingProbability).orElse(1.0);
                                    return 1.0 / probability;
                                }
                                case 5, 6, 7 -> {
                                    final var selectedRangeMin = Optionals.of(rawFlow.hashSelectedRangeMin).orElse(UnsignedLong.ZERO);
                                    final var selectedRangeMax = Optionals.of(rawFlow.hashSelectedRangeMax).orElse(UnsignedLong.MAX_VALUE);
                                    final var outputRangeMin = Optionals.of(rawFlow.hashOutputRangeMin).orElse(UnsignedLong.ZERO);
                                    final var outputRangeMax = Optionals.of(rawFlow.hashOutputRangeMax).orElse(UnsignedLong.MAX_VALUE);
                                    return (outputRangeMax.minus(outputRangeMin)).dividedBy(selectedRangeMax.minus(selectedRangeMin)).doubleValue();
                                }
                                case null, default -> {
                                    return 1.0;
                                }
                            }
                        });
            }

            @Override
            public InetAddress getSrcAddr() {
                return Optionals.first(rawFlow.sourceIPv6Address, rawFlow.sourceIPv4Address).orElse(null);
            }

            @Override
            public long getSrcAs() {
                return Optionals.first(rawFlow.bgpSourceAsNumber).orElse(0L);
            }

            @Override
            public int getSrcMaskLen() {
                return Optionals.first(rawFlow.sourceIPv6PrefixLength, rawFlow.sourceIPv4PrefixLength).orElse(0);
            }

            @Override
            public int getSrcPort() {
                return Optionals.first(rawFlow.sourceTransportPort).orElse(0);
            }

            @Override
            public int getTcpFlags() {
                return Optionals.first(rawFlow.tcpControlBits).orElse(0);
            }

            @Override
            public int getTos() {
                return Optionals.first(rawFlow.ipClassOfService).orElse(0);
            }

            @Override
            public int getVlan() {
                return Optionals.first(rawFlow.vlanId, rawFlow.postVlanId, rawFlow.dot1qVlanId, rawFlow.dot1qCustomerVlanId, rawFlow.postDot1qVlanId, rawFlow.postDot1qCustomerVlanId).orElse(0);
            }

            @Override
            public FlowProtocol getFlowProtocol() {
                return FlowProtocol.IPFIX;
            }
        };
    }

    private Stream<IpfixRawFlow> createRawFlows(final Packet packet) {
        final int recordCount = packet.dataSets.stream()
                .mapToInt(s -> s.records.size())
                .sum();
        return packet.dataSets.stream().flatMap(ds -> ds.records.stream()).map(record -> {
            final var dummyFlow = new IpfixRawFlow();
            record.getValues().forEach(value -> conversionService.apply(value, dummyFlow));
            dummyFlow.recordCount = recordCount;
            dummyFlow.sequenceNumber = packet.header.sequenceNumber;
            dummyFlow.exportTime = Instant.ofEpochSecond(packet.header.exportTime);
            dummyFlow.observationDomainId = packet.header.observationDomainId;
            return dummyFlow;
        });
    }
}
