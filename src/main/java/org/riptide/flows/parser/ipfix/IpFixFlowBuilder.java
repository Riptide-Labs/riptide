package org.riptide.flows.parser.ipfix;

import com.google.common.primitives.UnsignedLong;
import org.riptide.flows.parser.data.Flow;
import org.riptide.flows.parser.data.FlowBuilder;
import org.riptide.flows.parser.data.Timeout;
import org.riptide.flows.parser.data.Values;
import org.riptide.flows.parser.ie.Value;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.riptide.flows.parser.data.Values.doubleValue;
import static org.riptide.flows.parser.data.Values.durationValue;
import static org.riptide.flows.parser.data.Values.inetAddressValue;
import static org.riptide.flows.parser.data.Values.intValue;
import static org.riptide.flows.parser.data.Values.longValue;
import static org.riptide.flows.parser.data.Values.timestampValue;
import static org.riptide.flows.parser.data.Values.unsignedLongValue;


public class IpFixFlowBuilder implements FlowBuilder {

    private Duration flowActiveTimeoutFallback;
    private Duration flowInactiveTimeoutFallback;
    private Long flowSamplingIntervalFallback; // TODO fooker: usage

    public IpFixFlowBuilder() {
    }

    @Override
    public Flow buildFlow(final Instant receivedAt,
                          final Map<String, Value<?>> values) {

        // TODO fooker: What about @observationDomainId

        // TODO fooker: Structurize meta info
        final var timestamp = timestampValue("@exportTime", ChronoUnit.SECONDS);

        final var systemInitTime = timestampValue("systemInitTimeMilliseconds");

        return new Flow() {
            @Override
            public Instant getReceivedAt() {
                return receivedAt;
            }

            @Override
            public Instant getTimestamp() {
                return timestamp.getOrNull(values);
            }

            public Long getBytes() {
                return Values.<Long>first(values)
                        .with(longValue("octetDeltaCount"))
                        .with(longValue("postOctetDeltaCount"))
                        .with(longValue("layer2OctetDeltaCount"))
                        .with(longValue("postLayer2OctetDeltaCount"))
                        .with(longValue("transportOctetDeltaCount"))
                        .getOrNull();
            }

            @Override
            public Direction getDirection() {
                final var direction = Values.<Integer>first(values)
                        .with(intValue("flowDirection")).getOrNull();
                if (direction == null) {
                    return Direction.UNKNOWN;
                }

                return switch (direction) {
                    case 0 -> Direction.INGRESS;
                    case 1 -> Direction.EGRESS;
                    default -> Direction.UNKNOWN;
                };
            }

            @Override
            public InetAddress getDstAddr() {
                return Values.<InetAddress>first(values)
                        .with(inetAddressValue("destinationIPv6Address"))
                        .with(inetAddressValue("destinationIPv4Address"))
                        .getOrNull();
            }

            @Override
            public Long getDstAs() {
                return Values.<Long>first(values)
                        .with(longValue("bgpDestinationAsNumber"))
                        .getOrNull();
            }

            @Override
            public Integer getDstMaskLen() {
                return Values.<Integer>first(values)
                        .with(intValue("destinationIPv6PrefixLength"))
                        .with(intValue("destinationIPv4PrefixLength"))
                        .getOrNull();
            }

            @Override
            public Integer getDstPort() {
                return Values.<Integer>first(values)
                        .with(intValue("destinationTransportPort"))
                        .getOrNull();
            }

            @Override
            public Integer getEngineId() {
                return Values.<Integer>first(values)
                        .with(intValue("engineId"))
                        .getOrNull();
            }

            @Override
            public Integer getEngineType() {
                return Values.<Integer>first(values)
                        .with(intValue("engineType"))
                        .getOrNull();
            }

            @Override
            public Instant getDeltaSwitched() {
                final var flowActiveTimeout = Values.<Duration>first(values)
                        .with(durationValue("flowActiveTimeout", ChronoUnit.SECONDS))
                        .with(IpFixFlowBuilder.this.flowActiveTimeoutFallback)
                        .getOrNull();

                final var flowInactiveTimeout = Values.<Duration>first(values)
                        .with(durationValue("flowInactiveTimeout", ChronoUnit.SECONDS))
                        .with(IpFixFlowBuilder.this.flowInactiveTimeoutFallback)
                        .getOrNull();

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
            public Instant getFirstSwitched() {
                return Values.<Instant>first(values)
                        .with(timestampValue("flowStartSeconds"))
                        .with(timestampValue("flowStartMilliseconds"))
                        .with(timestampValue("flowStartMicroseconds"))
                        .with(timestampValue("flowStartNanoseconds"))
                        .with(durationValue("flowStartDeltaMicroseconds", ChronoUnit.MICROS).and(timestamp, (delta, export) -> export.plus(delta)))
                        .with(durationValue("flowStartSysUpTime", ChronoUnit.MILLIS)
                                .and(systemInitTime, (offset, init) -> init.plus(offset)))
                        .getOrNull();
            }

            @Override
            public Instant getLastSwitched() {
                return Values.<Instant>first(values)
                        .with(timestampValue("flowEndSeconds"))
                        .with(timestampValue("flowEndMilliseconds"))
                        .with(timestampValue("flowEndMicroseconds"))
                        .with(timestampValue("flowEndNanoseconds"))
                        .with(durationValue("flowEndDeltaMicroseconds", ChronoUnit.MICROS).and(timestamp, (delta, export) -> export.plus(delta)))
                        .with(durationValue("flowEndSysUpTime", ChronoUnit.MILLIS)
                                .and(systemInitTime, (offset, init) -> init.plus(offset)))
                        .getOrNull();
            }

            @Override
            public int getFlowRecords() {
                // TODO fooker: Structurize meta info
                return Values.<Integer>first(values)
                        .with(intValue("@recordCount"))
                        .getOrNull();
            }

            @Override
            public long getFlowSeqNum() {
                // TODO fooker: Structurize meta info
                return Values.<Long>first(values)
                        .with(longValue("@sequenceNumber"))
                        .getOrNull();
            }

            @Override
            public Integer getInputSnmp() {
                return Values.<Integer>first(values)
                        .with(intValue("ingressPhysicalInterface"))
                        .with(intValue("ingressInterface"))
                        .getOrNull();
            }

            @Override
            public Integer getIpProtocolVersion() {
                return Values.<Integer>first(values)
                        .with(intValue("ipVersion"))
                        .getOrNull();
            }

            @Override
            public InetAddress getNextHop() {
                return Values.<InetAddress>first(values)
                        .with(inetAddressValue("ipNextHopIPv6Address"))
                        .with(inetAddressValue("ipNextHopIPv4Address"))
                        .with(inetAddressValue("bgpNextHopIPv6Address"))
                        .with(inetAddressValue("bgpNextHopIPv4Address"))
                        .getOrNull();
            }

            @Override
            public Integer getOutputSnmp() {
                return Values.<Integer>first(values)
                        .with(intValue("egressPhysicalInterface"))
                        .with(intValue("egressInterface"))
                        .getOrNull();
            }

            @Override
            public Long getPackets() {
                return Values.<Long>first(values)
                        .with(longValue("packetDeltaCount"))
                        .with(longValue("postPacketDeltaCount"))
                        .with(longValue("transportPacketDeltaCount"))
                        .getOrNull();
            }

            @Override
            public Integer getProtocol() {
                return Values.<Integer>first(values)
                        .with(intValue("protocolIdentifier"))
                        .getOrNull();
            }

            @Override
            public SamplingAlgorithm getSamplingAlgorithm() {
                final Integer deprecatedSamplingAlgorithm = Values.<Integer>first(values).with(
                        intValue("samplingAlgorithm")).with(
                        intValue("samplerMode")).getOrNull();
                if (deprecatedSamplingAlgorithm != null) {
                    if (deprecatedSamplingAlgorithm == 1) {
                        return Flow.SamplingAlgorithm.SystematicCountBasedSampling;
                    }
                    if (deprecatedSamplingAlgorithm == 2) {
                        return Flow.SamplingAlgorithm.RandomNOutOfNSampling;
                    }
                }

                final var selectorAlgorithm = Values.<Integer>first(values)
                        .with(intValue("selectorAlgorithm"))
                        .getOrNull();
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
                final Double deprecatedSamplingInterval = Values.<Double>first(values)
                        .with(doubleValue("samplingInterval"))
                        .with(doubleValue("samplerRandomInterval"))
                        .getOrNull();
                if (deprecatedSamplingInterval != null) {
                    return deprecatedSamplingInterval;
                }

                final var selectorAlgorithm = Values.<Integer>first(values)
                        .with(intValue("selectorAlgorithm"))
                        .getOrNull();
                switch (selectorAlgorithm) {
                    case 0, 8, 9 -> {
                        return Double.NaN;
                    }
                    case 1, 2 -> {
                        final var interval = Values.<Double>first(values)
                                .with(doubleValue("samplingFlowInterval", 1.0))
                                .with(doubleValue("flowSamplingTimeInterval", 1.0))
                                .getOrNull();
                        final var spacing = Values.<Double>first(values)
                                .with(doubleValue("samplingFlowSpacing", 0.0))
                                .with(doubleValue("flowSamplingTimeSpacing", 0.0))
                                .getOrNull();
                        return interval + spacing / interval;
                    }
                    case 3 -> {
                        final var size = doubleValue("samplingSize", 1.0).getOrNull(values);
                        final var population = doubleValue("samplingPopulation", 1.0).getOrNull(values);
                        return population / size;
                    }
                    case 4 -> {
                        final var probability = doubleValue("samplingProbability", 1.0).getOrNull(values);
                        return 1.0 / probability;
                    }
                    case 5, 6, 7 -> {
                        final var selectedRangeMin = unsignedLongValue("hashSelectedRangeMin", UnsignedLong.ZERO).getOrNull(values);
                        final var selectedRangeMax = unsignedLongValue("hashSelectedRangeMax", UnsignedLong.MAX_VALUE).getOrNull(values);
                        final var outputRangeMin = unsignedLongValue("hashOutputRangeMin", UnsignedLong.ZERO).getOrNull(values);
                        final var outputRangeMax = unsignedLongValue("hashOutputRangeMax", UnsignedLong.MAX_VALUE).getOrNull(values);
                        return (outputRangeMax.minus(outputRangeMin)).dividedBy(selectedRangeMax.minus(selectedRangeMin)).doubleValue();
                    }
                    case null, default -> {
                        return null;
                    }
                }
            }

            @Override
            public InetAddress getSrcAddr() {
                return Values.<InetAddress>first(values).with(
                        inetAddressValue("sourceIPv6Address")).with(
                        inetAddressValue("sourceIPv4Address")).getOrNull();
            }

            @Override
            public Long getSrcAs() {
                return Values.<Long>first(values)
                        .with(longValue("bgpSourceAsNumber"))
                        .getOrNull();
            }

            @Override
            public Integer getSrcMaskLen() {
                return Values.<Integer>first(values).with(
                        intValue("sourceIPv6PrefixLength")).with(
                        intValue("sourceIPv4PrefixLength")).getOrNull();
            }

            @Override
            public Integer getSrcPort() {
                return Values.<Integer>first(values)
                        .with(intValue("sourceTransportPort"))
                        .getOrNull();
            }

            @Override
            public Integer getTcpFlags() {
                return Values.<Integer>first(values)
                        .with(intValue("tcpControlBits"))
                        .getOrNull();
            }

            @Override
            public Integer getTos() {
                return Values.<Integer>first(values)
                        .with(intValue("ipClassOfService"))
                        .getOrNull();
            }

            @Override
            public FlowProtocol getFlowProtocol() {
                return FlowProtocol.IPFIX;
            }

            @Override
            public Integer getVlan() {
                return Values.<Integer>first(values).with(
                        intValue("vlanId")).with(
                        intValue("postVlanId")).with(
                        intValue("dot1qVlanId")).with(
                        intValue("dot1qCustomerVlanId")).with(
                        intValue("postDot1qVlanId")).with(
                        intValue("postDot1qCustomerVlanId")).getOrNull();
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
