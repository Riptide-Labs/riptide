package org.riptide.flows.parser.netflow9;

import org.riptide.flows.parser.data.Flow;
import org.riptide.flows.parser.data.Optionals;
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

                .firstSwitched(bootTime.plus(raw.FIRST_SWITCHED))
                .lastSwitched(bootTime.plus(raw.LAST_SWITCHED))

                .inputSnmp(raw.INPUT_SNMP)
                .outputSnmp(raw.OUTPUT_SNMP)

                .srcAs(raw.SRC_AS)
                .srcAddr(Optionals.first(raw.IPV6_SRC_ADDR, raw.IPV4_SRC_ADDR).orElse(null))
                .srcMaskLen(Optionals.first(raw.IPV6_SRC_MASK, raw.SRC_MASK).orElse(0))
                .srcPort(raw.L4_SRC_PORT)

                .dstAs(raw.DST_AS)
                .dstAddr(Optionals.first(raw.IPV6_DST_ADDR, raw.IPV4_DST_ADDR).orElse(null))
                .dstMaskLen(Optionals.first(raw.IPV6_DST_MASK, raw.DST_MASK).orElse(0))
                .dstPort(raw.L4_DST_PORT)

                .nextHop(Optionals.first(raw.IPV6_NEXT_HOP,
                                raw.IPV4_NEXT_HOP,
                                raw.BPG_IPV6_NEXT_HOP,
                                raw.BPG_IPV4_NEXT_HOP)
                        .orElse(null))

                .bytes(raw.IN_BYTES)
                .packets(raw.IN_PKTS)

                .direction(switch (raw.DIRECTION) {
                    case 0 -> Flow.Direction.INGRESS;
                    case 1 -> Flow.Direction.EGRESS;
                    case null, default -> Flow.Direction.UNKNOWN;
                })

                .engineId(raw.ENGINE_ID)
                .engineType(raw.ENGINE_TYPE)

                .vlan(Optionals.first(raw.SRC_VLAN, raw.DST_VLAN).orElse(0))
                .ipProtocolVersion(raw.IP_PROTOCOL_VERSION)
                .protocol(raw.PROTOCOL)
                .tcpFlags(raw.TCP_FLAGS)
                .tos(raw.TOS)

                .samplingAlgorithm(switch (raw.SAMPLING_ALGORITHM) {
                    case 1 -> Flow.SamplingAlgorithm.SystematicCountBasedSampling;
                    case 2 -> Flow.SamplingAlgorithm.RandomNOutOfNSampling;
                    case null, default -> Flow.SamplingAlgorithm.Unassigned;
                })
                .samplingInterval(raw.SAMPLING_INTERVAL)

                .build();
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
