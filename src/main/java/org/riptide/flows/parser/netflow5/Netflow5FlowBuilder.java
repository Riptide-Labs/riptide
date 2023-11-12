package org.riptide.flows.parser.netflow5;


import org.riptide.flows.parser.data.Flow;
import org.riptide.flows.parser.data.FlowBuilder;
import org.riptide.flows.parser.data.Values;
import org.riptide.flows.parser.ie.Value;

import java.net.InetAddress;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.riptide.flows.parser.data.Values.booleanValue;
import static org.riptide.flows.parser.data.Values.doubleValue;
import static org.riptide.flows.parser.data.Values.durationValue;
import static org.riptide.flows.parser.data.Values.inetAddressValue;
import static org.riptide.flows.parser.data.Values.intValue;
import static org.riptide.flows.parser.data.Values.longValue;

public class Netflow5FlowBuilder implements FlowBuilder {
    @Override
    public Flow buildFlow(final Instant receivedAt,
                          final Map<String, Value<?>> values) {

        final var timestamp = Values.both(
                longValue("@unixSecs"),
                longValue("@unixNSecs"),
                Instant::ofEpochSecond);

        final var bootTime = Values.both(timestamp, durationValue("@sysUptime", ChronoUnit.MILLIS), Instant::minus);

        return new Flow() {
            @Override
            public Instant getReceivedAt() {
                return receivedAt;
            }

            @Override
            public Instant getTimestamp() {
                return timestamp.getOrNull(values);
            }

            @Override
            public Long getBytes() {
                return longValue("dOctets").getOrNull(values);
            }

            @Override
            public Direction getDirection() {
                return booleanValue("egress").getOrNull(values)
                        ? Direction.EGRESS
                        : Direction.INGRESS;
            }

            @Override
            public InetAddress getDstAddr() {
                return inetAddressValue("dstAddr").getOrNull(values);
            }

            @Override
            public Long getDstAs() {
                return longValue("dstAs").getOrNull(values);
            }

            @Override
            public Integer getDstMaskLen() {
                return intValue("dstMask").getOrNull(values);
            }

            @Override
            public Integer getDstPort() {
                return intValue("dstPort").getOrNull(values);
            }

            @Override
            public Integer getEngineId() {
                return intValue("@engineId").getOrNull(values);
            }

            @Override
            public Integer getEngineType() {
                return intValue("@engineType").getOrNull(values);
            }

            @Override
            public Instant getFirstSwitched() {
                return bootTime.and(durationValue("first", ChronoUnit.MILLIS), Instant::plus).getOrNull(values);
            }

            @Override
            public int getFlowRecords() {
                return intValue("@count").getOrNull(values);
            }

            @Override
            public long getFlowSeqNum() {
                return longValue("@flowSequence").getOrNull(values);
            }

            @Override
            public Integer getInputSnmp() {
                return intValue("input").getOrNull(values);
            }

            @Override
            public Integer getIpProtocolVersion() {
                return null;
            }

            @Override
            public Instant getLastSwitched() {
                return bootTime.and(durationValue("last", ChronoUnit.MILLIS), Instant::plus).getOrNull(values);
            }

            @Override
            public InetAddress getNextHop() {
                return inetAddressValue("nextHop").getOrNull(values);
            }

            @Override
            public Integer getOutputSnmp() {
                return intValue("output").getOrNull(values);
            }

            @Override
            public Long getPackets() {
                return longValue("dPkts").getOrNull(values);
            }

            @Override
            public Integer getProtocol() {
                return intValue("proto").getOrNull(values);
            }

            @Override
            public SamplingAlgorithm getSamplingAlgorithm() {
                final var saValue = intValue("@samplingAlgorithm").getOrNull(values);
                return switch (saValue) {
                    case 1 -> Flow.SamplingAlgorithm.SystematicCountBasedSampling;
                    case 2 -> Flow.SamplingAlgorithm.RandomNOutOfNSampling;
                    case null, default -> Flow.SamplingAlgorithm.Unassigned;
                };
            }

            @Override
            public Double getSamplingInterval() {
                return doubleValue("@samplingInterval", Double.NaN).getOrNull(values);
            }

            @Override
            public InetAddress getSrcAddr() {
                return inetAddressValue("srcAddr").getOrNull(values);
            }

            @Override
            public Long getSrcAs() {
                return longValue("srcAs").getOrNull(values);
            }

            @Override
            public Integer getSrcMaskLen() {
                return intValue("srcMask").getOrNull(values);
            }

            @Override
            public Integer getSrcPort() {
                return intValue("srcPort").getOrNull(values);
            }

            @Override
            public Integer getTcpFlags() {
                return intValue("tcpFlags").getOrNull(values);
            }

            @Override
            public Integer getTos() {
                return intValue("tos").getOrNull(values);
            }

            @Override
            public FlowProtocol getFlowProtocol() {
                return FlowProtocol.NetflowV5;
            }

            @Override
            public Integer getVlan() {
                return null;
            }
        };
    }
}
