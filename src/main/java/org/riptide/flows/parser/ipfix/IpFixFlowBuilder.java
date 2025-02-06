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

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import static org.riptide.flows.parser.data.Flow.Direction;
import static org.riptide.flows.parser.data.Flow.FlowProtocol;
import static org.riptide.flows.parser.data.Flow.SamplingAlgorithm;


@Slf4j
public class IpFixFlowBuilder {

    private final ValueConversionService conversionService;
    @Getter @Setter private Duration flowActiveTimeoutFallback;
    @Getter @Setter private Duration flowInactiveTimeoutFallback;
    @Getter @Setter private Long flowSamplingIntervalFallback;

    public IpFixFlowBuilder(ValueConversionService conversionService) {
        this.conversionService = Objects.requireNonNull(conversionService);
    }

    public Stream<Flow> buildFlows(final Instant receivedAt, final Packet packet) {
        return createRawFlows(packet)
                .map(rawFlow -> buildFlow(receivedAt, rawFlow));
    }

    public Flow buildFlow(Instant receivedAt, IpfixRawFlow rawFlow) {
        // TODO fooker: What about @observationDomainId

        // TODO fooker: Structurize meta info
        final var direction = switch (rawFlow.flowDirection) {
            case 0 -> Direction.INGRESS;
            case 1 -> Direction.EGRESS;
            case null, default -> Direction.UNKNOWN;
        };

        final var flowActiveTimeout = Optionals.first(rawFlow.flowActiveTimeout, flowActiveTimeoutFallback).orElse(null);
        final var flowInactiveTimeout = Optionals.first(rawFlow.flowInactiveTimeout, flowInactiveTimeoutFallback).orElse(null);
        final var firstSwitched = Optionals.first(
                        rawFlow.flowStartSeconds,
                        rawFlow.flowStartMilliseconds,
                        rawFlow.flowStartMicroseconds,
                        rawFlow.flowStartNanoseconds)
                .or(() ->
                        Optionals.first(
                                Optional.ofNullable(rawFlow.flowStartDeltaMicroseconds)
                                        .map(it -> rawFlow.exportTime.plus(it)),
                                Optional.ofNullable(rawFlow.flowStartSysUpTime)
                                        .map(it -> rawFlow.systemInitTimeMilliseconds.plus(it))
                        ).orElse(Optional.empty()))
                .orElse(null);
        final var lastSwitched = Optionals.first(
                        rawFlow.flowEndSeconds,
                        rawFlow.flowEndMilliseconds,
                        rawFlow.flowEndMicroseconds,
                        rawFlow.flowEndNanoseconds)
                .or(() -> Optionals.first(
                        Optional.ofNullable(rawFlow.flowEndDeltaMicroseconds)
                                .map(it -> rawFlow.exportTime.plus(it)),
                        Optional.ofNullable(rawFlow.flowEndSysUpTime)
                                .map(it -> rawFlow.systemInitTimeMilliseconds.plus(it))
                ).orElse(Optional.empty()))
                .orElse(null);

        final var bytes = Optionals.first(
                rawFlow.octetDeltaCount,
                rawFlow.postOctetDeltaCount,
                rawFlow.layer2OctetDeltaCount,
                rawFlow.postLayer2OctetDeltaCount,
                rawFlow.transportOctetDeltaCount).orElse(0L);

        final var packets = Optionals.first(rawFlow.packetDeltaCount, rawFlow.postPacketDeltaCount, rawFlow.transportPacketDeltaCount).orElse(0L);

        final var deltaSwitched = new Timeout()
                .withActiveTimeout(flowActiveTimeout)
                .withInactiveTimeout(flowInactiveTimeout)
                .withFirstSwitched(firstSwitched)
                .withLastSwitched(lastSwitched)
                .withNumBytes(bytes)
                .withNumPackets(packets)
                .calculateDeltaSwitched();
        return Flow.builder()
                .receivedAt(receivedAt)
                .timestamp(rawFlow.exportTime)
                .bytes(bytes)
                .direction(direction)
                .dstAddr(Optionals.first(rawFlow.destinationIPv6Address, rawFlow.destinationIPv4Address).orElse(null))
                .dstAs(Optionals.first(rawFlow.bgpDestinationAsNumber).orElse(0L))
                .dstMaskLen(Optionals.first(rawFlow.destinationIPv6PrefixLength, rawFlow.destinationIPv4PrefixLength).orElse(0))
                .dstPort(Optionals.first(rawFlow.destinationTransportPort).orElse(0))
                .engineId(Optionals.first(rawFlow.engineId).orElse(0))
                .engineType(Optionals.first(rawFlow.engineType).orElse(0))
                .firstSwitched(firstSwitched)
                .lastSwitched(lastSwitched)
                .deltaSwitched(deltaSwitched)
                .flowRecords(rawFlow.recordCount)
                .flowSeqNum(rawFlow.sequenceNumber)
                .inputSnmp(Optionals.first(rawFlow.ingressPhysicalInterface, rawFlow.ingressInterface).orElse(0))
                .ipProtocolVersion(Optionals.first(rawFlow.ipVersion).orElse(0))
                .nextHop(Optionals.first(rawFlow.ipNextHopIPv6Address, rawFlow.ipNextHopIPv4Address, rawFlow.bgpNextHopIPv6Address, rawFlow.bgpNextHopIPv4Address).orElse(null))
                .outputSnmp(Optionals.first(rawFlow.egressPhysicalInterface, rawFlow.egressInterface).orElse(0))
                .packets(packets)
                .protocol(Optionals.first(rawFlow.protocolIdentifier).orElse(0))
                .samplingAlgorithm(
                        Optionals.first(rawFlow.samplingAlgorithm, rawFlow.samplerMode)
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
                                }).orElse(null)
                )
                .samplingInterval(
                        Optionals.first(rawFlow.samplingInterval, rawFlow.samplerRandomInterval)
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
                                            return 0.0;
                                        }
                                    }
                                }))
                .srcAddr(Optionals.first(rawFlow.sourceIPv6Address, rawFlow.sourceIPv4Address).orElse(null))
                .srcAs(Optionals.first(rawFlow.bgpSourceAsNumber).orElse(0L))
                .srcMaskLen(Optionals.first(rawFlow.sourceIPv6PrefixLength, rawFlow.sourceIPv4PrefixLength).orElse(0))
                .srcPort(Optionals.first(rawFlow.sourceTransportPort).orElse(0))
                .tcpFlags(Optionals.first(rawFlow.tcpControlBits).orElse(0))
                .tos(Optionals.first(rawFlow.ipClassOfService).orElse(0))
                .vlan(Optionals.first(rawFlow.vlanId, rawFlow.postVlanId, rawFlow.dot1qVlanId, rawFlow.dot1qCustomerVlanId, rawFlow.postDot1qVlanId, rawFlow.postDot1qCustomerVlanId).orElse(0))
                .flowProtocol(FlowProtocol.IPFIX)
                .build();
    }

    private Stream<IpfixRawFlow> createRawFlows(Packet packet) {
        final int recordCount = packet.dataSets.stream()
                .mapToInt(s -> s.records.size())
                .sum();
        return packet.dataSets.stream().flatMap(ds -> ds.records.stream()).map(record -> {
            final var dummyFlow = new IpfixRawFlow();
            for (var value : record.getValues()) {
                conversionService.convert(value, dummyFlow);
            }
            dummyFlow.recordCount = recordCount;
            dummyFlow.sequenceNumber = packet.header.sequenceNumber;
            dummyFlow.exportTime = Instant.ofEpochSecond(packet.header.exportTime);
            dummyFlow.observationDomainId = packet.header.observationDomainId;
            return dummyFlow;
        });
    }
}
