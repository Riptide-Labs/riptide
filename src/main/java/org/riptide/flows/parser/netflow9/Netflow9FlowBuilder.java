package org.riptide.flows.parser.netflow9;

import org.riptide.flows.parser.data.Flow;
import org.riptide.flows.parser.data.Timeout;
import org.riptide.flows.parser.data.Values;
import org.riptide.flows.parser.netflow9.proto.Header;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.riptide.flows.parser.data.Values.doubleValue;
import static org.riptide.flows.parser.data.Values.durationValue;
import static org.riptide.flows.parser.data.Values.inetAddressValue;
import static org.riptide.flows.parser.data.Values.intValue;
import static org.riptide.flows.parser.data.Values.longValue;
import static org.riptide.flows.parser.data.Values.timestampValue;

public class Netflow9FlowBuilder {

    private Duration flowActiveTimeoutFallback;
    private Duration flowInactiveTimeoutFallback;
    private Long flowSamplingIntervalFallback;

    public Flow buildFlow(final Instant receivedAt,
                          final Header header,
                          final Netflow9RawFlow raw) {
        final var sysUpTime = Duration.ofMillis(header.sysUpTime);
        final var unixSecs = Instant.ofEpochSecond(header.unixSecs);

        final var bootTime = unixSecs.minus(sysUpTime);

        return Flow.builder()
                .receivedAt(receivedAt)

                .timestamp(unixSecs)

                .flowProtocol(Flow.FlowProtocol.NetflowV9)
                .flowRecords(header.count)
                .flowSeqNum(header.sequenceNumber)

                .firstSwitched(bootTime.plus(raw.FIRST_SWITCHED, ChronoUnit.MILLIS))
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

                .samplingAlgorithm(switch (header.samplingAlgorithm) {
                    case 1 -> Flow.SamplingAlgorithm.SystematicCountBasedSampling;
                    case 2 -> Flow.SamplingAlgorithm.RandomNOutOfNSampling;
                    default -> Flow.SamplingAlgorithm.Unassigned;
                })
                .samplingInterval(header.samplingInterval)

                .build();

        return new Flow() {
            @Override
            public Instant getReceivedAt() {
                return receivedAt;
            }

            @Override
            public Instant getTimestamp() {
                return unixSecs.getOrNull(values);
            }

            @Override
            public Long getBytes() {
                return longValue("IN_BYTES").getOrNull(values);
            }

            @Override
            public Direction getDirection() {
                return switch (intValue("DIRECTION").getOrNull(values)) {
                    case 0 -> Flow.Direction.INGRESS;
                    case 1 -> Flow.Direction.EGRESS;
                    case null, default -> Flow.Direction.UNKNOWN;
                };
            }

            @Override
            public InetAddress getDstAddr() {
                return Values.<InetAddress>first(values)
                        .with(inetAddressValue("IPV6_DST_ADDR"))
                        .with(inetAddressValue("IPV4_DST_ADDR"))
                        .getOrNull();
            }

            @Override
            public Long getDstAs() {
                return longValue("DST_AS").getOrNull(values);
            }

            @Override
            public Integer getDstMaskLen() {
                return Values.<Integer>first(values)
                        .with(intValue("IPV6_DST_MASK"))
                        .with(intValue("DST_MASK"))
                        .getOrNull();
            }

            @Override
            public Integer getDstPort() {
                return intValue("L4_DST_PORT").getOrNull(values);
            }

            @Override
            public Integer getEngineId() {
                return intValue("ENGINE_ID").getOrNull(values);
            }

            @Override
            public Integer getEngineType() {
                return intValue("ENGINE_TYPE").getOrNull(values);
            }

            @Override
            public Instant getDeltaSwitched() {
                return new Timeout()
                        .withActiveTimeout(Values.<Duration>first(values)
                                .with(durationValue("FLOW_ACTIVE_TIMEOUT", ChronoUnit.SECONDS))
                                .with(Netflow9FlowBuilder.this.flowActiveTimeoutFallback)
                                .getOrNull())
                        .withInactiveTimeout(Values.<Duration>first(values)
                                .with(durationValue("FLOW_INACTIVE_TIMEOUT", ChronoUnit.SECONDS))
                                .with(Netflow9FlowBuilder.this.flowInactiveTimeoutFallback)
                                .getOrNull())
                        .withFirstSwitched(this.getFirstSwitched())
                        .withLastSwitched(this.getLastSwitched())
                        .withNumBytes(this.getBytes())
                        .withNumPackets(this.getPackets())
                        .calculateDeltaSwitched();
            }

            @Override
            public Instant getFirstSwitched() {
                return Values.<Instant>first(values)
                        .with(bootTime.and(durationValue("FIRST_SWITCHED", ChronoUnit.MILLIS), Instant::plus))
                        .with(timestampValue("flowStartMilliseconds", ChronoUnit.MILLIS))
                        .getOrNull();
            }

            @Override
            public int getFlowRecords() {
                return intValue("@recordCount").getOrNull(values);
            }

            @Override
            public long getFlowSeqNum() {
                return longValue("@sequenceNumber").getOrNull(values);
            }

            @Override
            public Integer getInputSnmp() {
                return Values.<Integer>first(values)
                        .with(intValue("ingressPhysicalInterface"))
                        .with(intValue("INPUT_SNMP"))
                        .getOrNull();
            }

            @Override
            public Integer getIpProtocolVersion() {
                return intValue("IP_PROTOCOL_VERSION").getOrNull(values);
            }

            @Override
            public Instant getLastSwitched() {
                return Values.<Instant>first(values)
                        .with(bootTime.and(durationValue("LAST_SWITCHED", ChronoUnit.MILLIS), Instant::plus))
                        .with(timestampValue("flowEndMilliseconds", ChronoUnit.MILLIS))
                        .getOrNull();
            }

            @Override
            public InetAddress getNextHop() {
                return Values.<InetAddress>first(values)
                        .with(inetAddressValue("IPV6_NEXT_HOP"))
                        .with(inetAddressValue("IPV4_NEXT_HOP"))
                        .with(inetAddressValue("BPG_IPV6_NEXT_HOP"))
                        .with(inetAddressValue("BPG_IPV4_NEXT_HOP"))
                        .getOrNull();
            }

            @Override
            public Integer getOutputSnmp() {
                return Values.<Integer>first(values)
                        .with(intValue("egressPhysicalInterface"))
                        .with(intValue("OUTPUT_SNMP"))
                        .getOrNull();
            }

            @Override
            public Long getPackets() {
                return longValue("IN_PKTS").getOrNull(values);
            }

            @Override
            public Integer getProtocol() {
                return intValue("PROTOCOL").getOrNull(values);
            }

            @Override
            public SamplingAlgorithm getSamplingAlgorithm() {
                return switch (intValue("SAMPLING_ALGORITHM").getOrNull(values)) {
                    case 1 -> Flow.SamplingAlgorithm.SystematicCountBasedSampling;
                    case 2 -> Flow.SamplingAlgorithm.RandomNOutOfNSampling;
                    case null, default -> Flow.SamplingAlgorithm.Unassigned;
                };
            }

            @Override
            public Double getSamplingInterval() {
                return doubleValue("SAMPLING_INTERVAL").getOrNull(values);
            }

            @Override
            public InetAddress getSrcAddr() {
                return Values.<InetAddress>first(values)
                        .with(inetAddressValue("IPV6_SRC_ADDR"))
                        .with(inetAddressValue("IPV4_SRC_ADDR"))
                        .getOrNull();
            }

            @Override
            public Long getSrcAs() {
                return longValue("SRC_AS").getOrNull(values);
            }

            @Override
            public Integer getSrcMaskLen() {
                return Values.<Integer>first(values)
                        .with(intValue("IPV6_SRC_MASK"))
                        .with(intValue("SRC_MASK"))
                        .getOrNull();
            }

            @Override
            public Integer getSrcPort() {
                return intValue("L4_SRC_PORT").getOrNull(values);
            }

            @Override
            public Integer getTcpFlags() {
                return intValue("TCP_FLAGS").getOrNull(values);
            }

            @Override
            public Integer getTos() {
                return intValue("TOS").getOrNull(values);
            }

            @Override
            public FlowProtocol getFlowProtocol() {
                return FlowProtocol.NetflowV9;
            }

            @Override
            public Integer getVlan() {
                return Values.<Integer>first(values)
                        .with(intValue("SRC_VLAN"))
                        .with(intValue("DST_VLAN"))
                        .getOrNull();
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
