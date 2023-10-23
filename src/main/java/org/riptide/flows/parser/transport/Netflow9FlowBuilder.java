package org.riptide.flows.parser.transport;

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

public class Netflow9FlowBuilder implements FlowBuilder {

    private Duration flowActiveTimeoutFallback;
    private Duration flowInactiveTimeoutFallback;
    private Long flowSamplingIntervalFallback;

    @Override
    public Flow buildFlow(final Instant receivedAt,
                          final Map<String, Value<?>> values,
                          final RecordEnrichment enrichment) {
        final var sourceId = longValue(values, "@sourceId");
        final var sysUpTime = durationValue(values, "@sysUpTime", ChronoUnit.MILLIS);
        final var unixSecs = timestampValue(values, "@unixSecs", ChronoUnit.SECONDS);

        final var firstSwitched = durationValue(values, "FIRST_SWITCHED", ChronoUnit.MILLIS);
        final var lastSwitched = durationValue(values, "LAST_SWITCHED", ChronoUnit.MILLIS);
        final var ipv6SrcAddress = inetAddressValue(values, "IPV6_SRC_ADDR");
        final var ipv4SrcAddress = inetAddressValue(values, "IPV4_SRC_ADDR");
        final var flowActiveTimeout = durationValue(values, "FLOW_ACTIVE_TIMEOUT", ChronoUnit.SECONDS);
        final var flowInactiveTimeout = durationValue(values, "FLOW_INACTIVE_TIMEOUT", ChronoUnit.SECONDS);
        final var flowStartMilliseconds = timestampValue(values, "flowStartMilliseconds", ChronoUnit.MILLIS);
        final var flowEndMilliseconds = timestampValue(values, "flowEndMilliseconds", ChronoUnit.MILLIS);

        final var bootTime = unixSecs.minus(sysUpTime);

        return new Flow() {
            @Override
            public Instant getReceivedAt() {
                return receivedAt;
            }

            @Override
            public Instant getTimestamp() {
                return unixSecs;
            }

            @Override
            public Long getNumBytes() {
                return longValue(values, "IN_BYTES");
            }

            @Override
            public Direction getDirection() {
                return switch (intValue(values, "DIRECTION")) {
                    case 0 -> Flow.Direction.INGRESS;
                    case 1 -> Flow.Direction.EGRESS;
                    case null, default -> Flow.Direction.UNKNOWN;
                };
            }

            @Override
            public InetAddress getDstAddr() {
                return first(
                        inetAddressValue(values, "IPV6_DST_ADDR"),
                        inetAddressValue(values, "IPV4_DST_ADDR"));
            }

            @Override
            public Optional<String> getDstAddrHostname() {
                return enrichment.getHostnameFor(this.getDstAddr());
            }

            @Override
            public Long getDstAs() {
                return longValue(values, "DST_AS");
            }

            @Override
            public Integer getDstMaskLen() {
                return first(
                        intValue(values, "IPV6_DST_MASK"),
                        intValue(values, "DST_MASK"));
            }

            @Override
            public Integer getDstPort() {
                return intValue(values, "L4_DST_PORT");
            }

            @Override
            public Integer getEngineId() {
                return intValue(values, "ENGINE_ID");
            }

            @Override
            public Integer getEngineType() {
                return intValue(values, "ENGINE_TYPE");
            }

            @Override
            public Instant getDeltaSwitched() {
                final Timeout timeout = new Timeout(
                        first(Netflow9FlowBuilder.this.flowActiveTimeoutFallback, flowActiveTimeout),
                        first(Netflow9FlowBuilder.this.flowInactiveTimeoutFallback, flowInactiveTimeout));
                timeout.setFirstSwitched(this.getFirstSwitched());
                timeout.setLastSwitched(this.getLastSwitched());
                timeout.setNumBytes(this.getNumBytes());
                timeout.setNumPackets(this.getNumPackets());
                return timeout.getDeltaSwitched();
            }

            @Override
            public Instant getFirstSwitched() {
                if (firstSwitched != null) {
                    return bootTime.plus(firstSwitched);
                } else {
                    // Some Cisco platforms also support absolute timestamps in NetFlow v9 (like defined in IPFIX). See NMS-13006
                    return flowStartMilliseconds;
                }
            }

            @Override
            public int getFlowRecords() {
                return intValue(values, "@recordCount");
            }

            @Override
            public long getFlowSeqNum() {
                return longValue(values, "@sequenceNumber");
            }

            @Override
            public Integer getInputSnmp() {
                return first(
                        intValue(values, "ingressPhysicalInterface"),
                        intValue(values, "INPUT_SNMP"));
            }

            @Override
            public Integer getIpProtocolVersion() {
                return intValue(values, "IP_PROTOCOL_VERSION");
            }

            @Override
            public Instant getLastSwitched() {
                if (lastSwitched != null) {
                    return bootTime.plus(lastSwitched);
                } else {
                    // Some Cisco platforms also support absolute timestamps in NetFlow v9 (like defined in IPFIX). See NMS-13006
                    return flowEndMilliseconds;
                }
            }

            @Override
            public InetAddress getNextHop() {
                return first(
                        inetAddressValue(values, "IPV6_NEXT_HOP"),
                        inetAddressValue(values, "IPV4_NEXT_HOP"),
                        inetAddressValue(values, "BPG_IPV6_NEXT_HOP"),
                        inetAddressValue(values, "BPG_IPV4_NEXT_HOP"));
            }

            @Override
            public Optional<String> getNextHopHostname() {
                return enrichment.getHostnameFor(this.getNextHop());
            }

            @Override
            public Integer getOutputSnmp() {
                return first(
                        intValue(values, "egressPhysicalInterface"),
                        intValue(values, "OUTPUT_SNMP"));
            }

            @Override
            public Long getNumPackets() {
                return longValue(values, "IN_PKTS");
            }

            @Override
            public Integer getProtocol() {
                return intValue(values, "PROTOCOL");
            }

            @Override
            public SamplingAlgorithm getSamplingAlgorithm() {
                return switch (intValue(values, "SAMPLING_ALGORITHM")) {
                    case 1 -> Flow.SamplingAlgorithm.SystematicCountBasedSampling;
                    case 2 -> Flow.SamplingAlgorithm.RandomNOutOfNSampling;
                    case null, default -> Flow.SamplingAlgorithm.Unassigned;
                };
            }

            @Override
            public Double getSamplingInterval() {
                return doubleValue(values, "SAMPLING_INTERVAL");
            }

            @Override
            public InetAddress getSrcAddr() {
                return first(ipv6SrcAddress, ipv4SrcAddress);
            }

            @Override
            public Optional<String> getSrcAddrHostname() {
                return enrichment.getHostnameFor(this.getSrcAddr());
            }

            @Override
            public Long getSrcAs() {
                return longValue(values, "SRC_AS");
            }

            @Override
            public Integer getSrcMaskLen() {
                return first(
                        intValue(values, "IPV6_SRC_MASK"),
                        intValue(values, "SRC_MASK"));
            }

            @Override
            public Integer getSrcPort() {
                return intValue(values, "L4_SRC_PORT");
            }

            @Override
            public Integer getTcpFlags() {
                return intValue(values, "TCP_FLAGS");
            }

            @Override
            public Integer getTos() {
                return intValue(values, "TOS");
            }

            @Override
            public NetflowVersion getNetflowVersion() {
                return NetflowVersion.V9;
            }

            @Override
            public Integer getVlan() {
                return first(
                        intValue(values, "SRC_VLAN"),
                        intValue(values, "DST_VLAN"));
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
