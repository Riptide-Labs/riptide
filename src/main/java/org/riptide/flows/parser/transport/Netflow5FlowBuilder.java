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

public class Netflow5FlowBuilder implements FlowBuilder {
    @Override
    public Flow buildFlow(final Instant receivedAt,
                          final Map<String, Value<?>> values,
                          final RecordEnrichment enrichment) {

        final var timeStamp = Instant.now().plus(Duration.ofSeconds(
                longValue(values, "@unixSecs"),
                longValue(values, "@unixNSecs")));

        final var sysUpTime = durationValue(values, "@sysUptime", ChronoUnit.MILLIS);

        final var bootTime = timeStamp.minus(sysUpTime);

        final var first = bootTime.plus(durationValue(values, "first", ChronoUnit.MILLIS));
        final var last = bootTime.plus(durationValue(values, "last", ChronoUnit.MILLIS));

        return new Flow() {
            @Override
            public Instant getReceivedAt() {
                return receivedAt;
            }

            @Override
            public Instant getTimestamp() {
                return null;
            }

            @Override
            public Long getNumBytes() {
                return longValue(values, "dOctets");
            }

            @Override
            public Direction getDirection() {
                return booleanValue(values, "egress")
                        ? Direction.EGRESS
                        : Direction.INGRESS;
            }

            @Override
            public InetAddress getDstAddr() {
                return inetAddressValue(values, "dstAddr");
            }

            @Override
            public Optional<String> getDstAddrHostname() {
                return enrichment.getHostnameFor(this.getDstAddr());
            }

            @Override
            public Long getDstAs() {
                return longValue(values, "dstAs");
            }

            @Override
            public Integer getDstMaskLen() {
                return intValue(values, "dstMask");
            }

            @Override
            public Integer getDstPort() {
                return intValue(values, "dstPort");
            }

            @Override
            public Integer getEngineId() {
                return intValue(values, "@engineId");
            }

            @Override
            public Integer getEngineType() {
                return intValue(values, "@engineType");
            }

            @Override
            public Instant getDeltaSwitched() {
                return null;
            }

            @Override
            public Instant getFirstSwitched() {
                return first;
            }

            @Override
            public int getFlowRecords() {
                return intValue(values, "@count");
            }

            @Override
            public long getFlowSeqNum() {
                return longValue(values, "@flowSequence");
            }

            @Override
            public Integer getInputSnmp() {
                return intValue(values, "input");
            }

            @Override
            public Integer getIpProtocolVersion() {
                return null;
            }

            @Override
            public Instant getLastSwitched() {
                return last;
            }

            @Override
            public InetAddress getNextHop() {
                return inetAddressValue(values, "nextHop");
            }

            @Override
            public Optional<String> getNextHopHostname() {
                return enrichment.getHostnameFor(this.getNextHop());
            }

            @Override
            public Integer getOutputSnmp() {
                return intValue(values, "output");
            }

            @Override
            public Long getNumPackets() {
                return longValue(values, "dPkts");
            }

            @Override
            public Integer getProtocol() {
                return intValue(values, "proto");
            }

            @Override
            public SamplingAlgorithm getSamplingAlgorithm() {
                final var saValue = intValue(values, "@samplingAlgorithm");
                return switch (saValue) {
                    case 1 -> Flow.SamplingAlgorithm.SystematicCountBasedSampling;
                    case 2 -> Flow.SamplingAlgorithm.RandomNOutOfNSampling;
                    case null, default -> Flow.SamplingAlgorithm.Unassigned;
                };
            }

            @Override
            public Double getSamplingInterval() {
                return doubleValue(values, "@samplingInterval", Double.NaN);
            }

            @Override
            public InetAddress getSrcAddr() {
                return inetAddressValue(values, "srcAddr");
            }

            @Override
            public Optional<String> getSrcAddrHostname() {
                return enrichment.getHostnameFor(this.getSrcAddr());
            }

            @Override
            public Long getSrcAs() {
                return longValue(values, "srcAs");
            }

            @Override
            public Integer getSrcMaskLen() {
                return intValue(values, "srcMask");
            }

            @Override
            public Integer getSrcPort() {
                return intValue(values, "srcPort");
            }

            @Override
            public Integer getTcpFlags() {
                return intValue(values, "tcpFlags");
            }

            @Override
            public Integer getTos() {
                return intValue(values, "tos");
            }

            @Override
            public NetflowVersion getNetflowVersion() {
                return NetflowVersion.V5;
            }

            @Override
            public Integer getVlan() {
                return null;
            }
        };
    }
}
