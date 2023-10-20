package org.riptide.flows.parser.transport;

import com.google.common.primitives.UnsignedLong;
import org.riptide.flows.Flow;
import org.riptide.flows.parser.RecordEnrichment;
import org.riptide.flows.parser.ie.Value;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;

import static org.riptide.flows.parser.transport.MessageUtils.*;

public class IpFixFlowBuilder implements FlowBuilder {

    private Duration flowActiveTimeoutFallback;
    private Duration flowInactiveTimeoutFallback;
    private Long flowSamplingIntervalFallback; // TODO fooker: usage

    public IpFixFlowBuilder() {
    }

    @Override
    public Flow buildFlow(final Instant receivedAt,
                          final Map<String, Value<?>> values,
                          final RecordEnrichment enrichment) {

        // TODO fooker: What about @observationDomainId

        return new Flow() {
            @Override
            public Instant getReceivedAt() {
                return receivedAt;
            }

            @Override
            public Instant getTimestamp() {
                // TODO: Structurize meta info
                return timeValue(values, "@exportTime");
            }

            public Long getNumBytes() {
                return first(
                        longValue(values, "octetDeltaCount"),
                        longValue(values, "postOctetDeltaCount"),
                        longValue(values, "layer2OctetDeltaCount"),
                        longValue(values, "postLayer2OctetDeltaCount"),
                        longValue(values, "transportOctetDeltaCount"));
            }

            @Override
            public Direction getDirection() {
                final var direction = longValue(values, "flowDirection");
                if (direction == null) {
                    return Direction.UNKNOWN;
                }

                return switch (direction.intValue()) {
                    case 0 -> Direction.INGRESS;
                    case 1 -> Direction.EGRESS;
                    default -> Direction.UNKNOWN;
                };
            }

            @Override
            public InetAddress getDstAddr() {
                return first(
                        inetAddressValue(values, "destinationIPv6Address"),
                        inetAddressValue(values, "destinationIPv4Address"));
            }

            @Override
            public Optional<String> getDstAddrHostname() {
                return enrichment.getHostnameFor(this.getDstAddr());
            }

            @Override
            public Long getDstAs() {
                return longValue(values, "bgpDestinationAsNumber");
            }

            @Override
            public Integer getDstMaskLen() {
                return first(
                        intValue(values, "destinationIPv6PrefixLength"),
                        intValue(values, "destinationIPv4PrefixLength"));
            }

            @Override
            public Integer getDstPort() {
                return intValue(values, "destinationTransportPort");
            }

            @Override
            public Integer getEngineId() {
                return intValue(values, "engineId");
            }

            @Override
            public Integer getEngineType() {
                return intValue(values, "engineType");
            }

            @Override
            public Instant getDeltaSwitched() {
                final var flowActiveTimeout = first(
                        durationValue(values, "flowActiveTimeout", ChronoUnit.SECONDS),
                        IpFixFlowBuilder.this.flowActiveTimeoutFallback);
                final var flowInactiveTimeout = first(
                        durationValue(values, "flowInactiveTimeout", ChronoUnit.SECONDS),
                        IpFixFlowBuilder.this.flowInactiveTimeoutFallback);

                final var timeout = new Timeout(flowActiveTimeout, flowInactiveTimeout);
                timeout.setFirstSwitched(this.getFirstSwitched());
                timeout.setLastSwitched(this.getLastSwitched());
                timeout.setNumBytes(this.getNumBytes());
                timeout.setNumPackets(this.getNumPackets());
                return timeout.getDeltaSwitched();
            }

            @Override
            public Instant getFirstSwitched() {
                final var flowStartDelta = Optional.ofNullable(longValue(values, "flowStartDeltaMicroseconds"))
                        .map(d -> this.getTimestamp().plus(d, ChronoUnit.MICROS));

                final var systemInitTime = Optional.ofNullable(timeValue(values, "systemInitTimeMilliseconds"));

                final var flowStartSysUpTime = Optional.ofNullable(longValue(values, "flowStartSysUpTime"))
                        .map(Duration::ofMillis)
                        .flatMap(offset -> systemInitTime.map(init -> init.plus(offset)));

                final var firstSwitchedInMilli = first(timestampValue(values, "flowStartSeconds", ChronoUnit.SECONDS),
                        timestampValue(values, "flowStartMilliseconds", ChronoUnit.MILLIS),
                        timestampValue(values, "flowStartMicroseconds", ChronoUnit.MICROS),
                        timestampValue(values, "flowStartNanoseconds", ChronoUnit.NANOS));

                return first(
                        Optional.ofNullable(firstSwitchedInMilli),
                        flowStartDelta,
                        flowStartSysUpTime
                ).orElse(null);
            }

            @Override
            public Instant getLastSwitched() {
                final var flowEndDelta = Optional.ofNullable(longValue(values, "flowEndDeltaMicroseconds"))
                        .map(d -> this.getTimestamp().plus(d, ChronoUnit.MICROS));

                final var systemInitTime = Optional.ofNullable(timeValue(values, "systemInitTimeMilliseconds"));

                final var flowEndSysUpTime = Optional.ofNullable(longValue(values, "flowEndSysUpTime"))
                        .map(Duration::ofMillis)
                        .flatMap(offset -> systemInitTime.map(init -> init.plus(offset)));

                final var lastSwitchedInMilli = first(timestampValue(values, "flowEndSeconds", ChronoUnit.SECONDS),
                        timestampValue(values, "flowEndMilliseconds", ChronoUnit.MILLIS),
                        timestampValue(values, "flowEndMicroseconds", ChronoUnit.MICROS),
                        timestampValue(values, "flowEndNanoseconds", ChronoUnit.NANOS));

                return first(
                        Optional.ofNullable(lastSwitchedInMilli),
                        flowEndDelta,
                        flowEndSysUpTime
                ).orElse(null);
            }

            @Override
            public int getFlowRecords() {
                // TODO: Structurize meta info
                return intValue(values, "@recordCount");
            }

            @Override
            public long getFlowSeqNum() {
                // TODO: Structurize meta info
                return longValue(values, "@sequenceNumber");
            }

            @Override
            public Integer getInputSnmp() {
                return first(
                        intValue(values, "ingressPhysicalInterface"),
                        intValue(values, "ingressInterface"));
            }

            @Override
            public Integer getIpProtocolVersion() {
                return intValue(values, "ipVersion");
            }

            @Override
            public InetAddress getNextHop() {
                return first(
                        inetAddressValue(values, "ipNextHopIPv6Address"),
                        inetAddressValue(values, "ipNextHopIPv4Address"),
                        inetAddressValue(values, "bgpNextHopIPv6Address"),
                        inetAddressValue(values, "bgpNextHopIPv4Address"));
            }

            @Override
            public Optional<String> getNextHopHostname() {
                return enrichment.getHostnameFor(this.getNextHop());
            }

            @Override
            public Integer getOutputSnmp() {
                return first(
                        intValue(values, "egressPhysicalInterface"),
                        intValue(values, "egressInterface"));
            }

            @Override
            public Long getNumPackets() {
                return first(
                        longValue(values, "packetDeltaCount"),
                        longValue(values, "postPacketDeltaCount"),
                        longValue(values, "transportPacketDeltaCount"));
            }

            @Override
            public Integer getProtocol() {
                return intValue(values, "protocolIdentifier");
            }

            @Override
            public SamplingAlgorithm getSamplingAlgorithm() {
                final Integer deprecatedSamplingAlgorithm = first(
                        intValue(values, "samplingAlgorithm"),
                        intValue(values, "samplerMode"));
                if (deprecatedSamplingAlgorithm != null) {
                    if (deprecatedSamplingAlgorithm == 1) {
                        return Flow.SamplingAlgorithm.SystematicCountBasedSampling;
                    }
                    if (deprecatedSamplingAlgorithm == 2) {
                        return Flow.SamplingAlgorithm.RandomNOutOfNSampling;
                    }
                }

                final var selectorAlgorithm = intValue(values, "selectorAlgorithm");
                return switch (selectorAlgorithm) {
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
            }

            @Override
            public Double getSamplingInterval() {
                final Double deprecatedSamplingInterval = first(
                        doubleValue(values, "samplingInterval"),
                        doubleValue(values, "samplerRandomInterval"));
                if (deprecatedSamplingInterval != null) {
                    return deprecatedSamplingInterval;
                }

                final var selectorAlgorithm = intValue(values, "selectorAlgorithm");
                switch (selectorAlgorithm) {
                    case 0, 8, 9 -> {
                        return Double.NaN;
                    }
                    case 1 -> {
                        final var interval = doubleValue(values, "samplingFlowInterval", 1.0);
                        final var spacing = doubleValue(values, "samplingFlowSpacing", 0.0);
                        return interval + spacing / interval;
                    }
                    case 2 -> {
                        final var interval = doubleValue(values, "flowSamplingTimeInterval", 1.0);
                        final var spacing = doubleValue(values, "flowSamplingTimeSpacing", 0.0);
                        return interval + spacing / interval;
                    }
                    case 3 -> {
                        final var size = doubleValue(values, "samplingSize", 1.0);
                        final var population = doubleValue(values, "samplingPopulation", 1.0);
                        return population / size;
                    }
                    case 4 -> {
                        final var probability = doubleValue(values, "samplingProbability", 1.0);
                        return 1.0 / probability;
                    }
                    case 5, 6, 7 -> {
                        final var selectedRangeMin = unsignedLongValue(values, "hashSelectedRangeMin", UnsignedLong.ZERO);
                        final var selectedRangeMax = unsignedLongValue(values, "hashSelectedRangeMax", UnsignedLong.MAX_VALUE);
                        final var outputRangeMin = unsignedLongValue(values, "hashOutputRangeMin", UnsignedLong.ZERO);
                        final var outputRangeMax = unsignedLongValue(values, "hashOutputRangeMax", UnsignedLong.MAX_VALUE);
                        return (outputRangeMax.minus(outputRangeMin)).dividedBy(selectedRangeMax.minus(selectedRangeMin)).doubleValue();
                    }
                    case null, default -> {
                        return null;
                    }
                }
            }

            @Override
            public InetAddress getSrcAddr() {
                return first(
                        inetAddressValue(values, "sourceIPv6Address"),
                        inetAddressValue(values, "sourceIPv4Address"));
            }

            @Override
            public Optional<String> getSrcAddrHostname() {
                return enrichment.getHostnameFor(this.getSrcAddr());
            }

            @Override
            public Long getSrcAs() {
                return longValue(values, "bgpSourceAsNumber");
            }

            @Override
            public Integer getSrcMaskLen() {
                return first(
                        intValue(values, "sourceIPv6PrefixLength"),
                        intValue(values, "sourceIPv4PrefixLength"));
            }

            @Override
            public Integer getSrcPort() {
                return intValue(values, "sourceTransportPort");
            }

            @Override
            public Integer getTcpFlags() {
                return intValue(values, "tcpControlBits");
            }

            @Override
            public Integer getTos() {
                return intValue(values, "ipClassOfService");
            }

            @Override
            public NetflowVersion getNetflowVersion() {
                return NetflowVersion.IPFIX;
            }

            @Override
            public Integer getVlan() {
                return first(
                        intValue(values, "vlanId"),
                        intValue(values, "postVlanId"),
                        intValue(values, "dot1qVlanId"),
                        intValue(values, "dot1qCustomerVlanId"),
                        intValue(values, "postDot1qVlanId"),
                        intValue(values, "postDot1qCustomerVlanId"));
            }
        };
    }

    public Duration getFlowActiveTimeoutFallback() {
        return this.flowActiveTimeoutFallback;
    }

    public void setFlowActiveTimeoutFallback(final Duration flowActiveTimeoutFallback) {
        this.flowActiveTimeoutFallback = flowActiveTimeoutFallback;
    }

    public Duration getFlowInactiveTimeoutFallback() {
        return this.flowInactiveTimeoutFallback;
    }

    public void setFlowInactiveTimeoutFallback(final Duration flowInactiveTimeoutFallback) {
        this.flowInactiveTimeoutFallback = flowInactiveTimeoutFallback;
    }

    public Long getFlowSamplingIntervalFallback() {
        return this.flowSamplingIntervalFallback;
    }

    public void setFlowSamplingIntervalFallback(final Long flowSamplingIntervalFallback) {
        this.flowSamplingIntervalFallback = flowSamplingIntervalFallback;
    }
}
