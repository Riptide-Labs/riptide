/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser.netflow9;

import lombok.Getter;
import lombok.Setter;
import org.riptide.flows.parser.ie.values.ValueConversionService;
import org.riptide.flows.parser.data.Flow;
import org.riptide.flows.parser.data.Flow.SamplingProvenance;
import org.riptide.flows.parser.data.Optionals;
import org.riptide.flows.parser.data.ResolvedRate;
import org.riptide.flows.parser.data.Timeout;
import org.riptide.flows.parser.netflow9.proto.Packet;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import org.riptide.flows.parser.session.ExporterSamplingTable;
import org.riptide.flows.parser.session.ExporterSamplingTable.AdvertisedRate;
import org.riptide.pipeline.ExporterIdentity;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
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

    /**
     * Rates this exporter advertised in a sampler options record. Optional: unset means no
     * correlation, and the ladder falls through to the configured fallback as before.
     */
    @Getter
    @Setter
    private ExporterSamplingTable samplingTable;

    public Netflow9FlowBuilder(final ValueConversionService conversionService) {
        this.conversionService = Objects.requireNonNull(conversionService);
    }

    /** Without an identity there is no exporter to look a learned rate up for. */
    public Stream<Flow> buildFlows(final Instant receivedAt,
                                   final Packet packet) {
        return buildFlows(receivedAt, packet, null);
    }

    /**
     * The exporter identity is threaded in rather than held on the builder: one UDP parser fronts
     * every exporter sending to its port, so a builder-scoped rate would be handed to whichever
     * exporter happened to send next.
     */
    public Stream<Flow> buildFlows(final Instant receivedAt,
                                   final Packet packet,
                                   final ExporterIdentity identity) {
        // Resolved lazily and at most once per packet: a record carrying its own rate never asks,
        // so an exporter that always states it does not register as a permanent lookup miss.
        final Supplier<Optional<AdvertisedRate>> advertised = Suppliers.memoize(
                () -> this.samplingTable != null ? this.samplingTable.lookup(identity) : Optional.empty());
        return createRawFlows(packet)
                .map(rawFlow -> buildFlow(receivedAt, rawFlow, advertised));
    }

    public Flow buildFlow(final Instant receivedAt,
                          final Netflow9RawFlow raw) {
        return buildFlow(receivedAt, raw, (AdvertisedRate) null);
    }

    public Flow buildFlow(final Instant receivedAt,
                          final Netflow9RawFlow raw,
                          final AdvertisedRate advertisedRate) {
        return buildFlow(receivedAt, raw, () -> Optional.ofNullable(advertisedRate));
    }

    private Flow buildFlow(final Instant receivedAt,
                           final Netflow9RawFlow raw,
                           final Supplier<Optional<AdvertisedRate>> advertised) {
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
                final var firstSwitched = Optionals.of(raw.FIRST_SWITCHED)
                        .map(this.getBootTime()::plus)
                        .orElse(raw.flowStartMilliseconds);
                // No FIRST_SWITCHED / flowStartMilliseconds exported: fall back to the export time (the
                // packet header timestamp), as goflow2 does — honouring the non-null Flow contract and
                // the non-nullable column.
                return firstSwitched != null ? firstSwitched : this.getTimestamp();
            }

            @Override
            public Instant getDeltaSwitched() {
                final var activeTimeout = Optionals.first(raw.FLOW_ACTIVE_TIMEOUT, flowActiveTimeoutFallback).orElse(null);
                final var inactiveTimeout = Optionals.first(raw.FLOW_INACTIVE_TIMEOUT, flowInactiveTimeoutFallback).orElse(null);

                final var delta = new Timeout()
                        .withActiveTimeout(activeTimeout)
                        .withInactiveTimeout(inactiveTimeout)
                        .withFirstSwitched(this.getFirstSwitched())
                        .withLastSwitched(this.getLastSwitched())
                        .withNumBytes(this.getBytes())
                        .withNumPackets(this.getPackets())
                        .calculateDeltaSwitched();
                // The timeout calc can yield null (no timeouts); default to firstSwitched like the
                // Flow interface, which is now non-null.
                return delta != null ? delta : this.getFirstSwitched();
            }

            @Override
            public Instant getLastSwitched() {
                final var lastSwitched = Optionals.of(raw.LAST_SWITCHED)
                        .map(this.getBootTime()::plus)
                        .orElse(raw.flowEndMilliseconds);
                // No LAST_SWITCHED / flowEndMilliseconds exported: fall back to the export time (see getFirstSwitched).
                return lastSwitched != null ? lastSwitched : this.getTimestamp();
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
                // Total counters last, as in the IPFIX builder: exporters using permanent-cache
                // counters (field 85/86) send no IN_BYTES/IN_PKTS.
                return Optionals.first(raw.IN_BYTES, raw.IN_PERMANENT_BYTES).orElse(0L);
            }

            @Override
            public long getPackets() {
                return Optionals.first(raw.IN_PKTS, raw.IN_PERMANENT_PKTS).orElse(0L);
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

            /**
             * The mode from a sampler record counts as well as field 35, as it does in the IPFIX
             * builder. Without it a record carrying only field 49 reports an interval alongside
             * {@code Unassigned}, which reads as self-contradictory.
             */
            @Override
            public Flow.SamplingAlgorithm getSamplingAlgorithm() {
                final Integer mode = Optionals.first(raw.SAMPLING_ALGORITHM, raw.FLOW_SAMPLER_MODE)
                        .or(() -> advertised.get().map(AdvertisedRate::mode))
                        .orElse(null);
                return switch (mode) {
                    case 1 -> Flow.SamplingAlgorithm.SystematicCountBasedSampling;
                    case 2 -> Flow.SamplingAlgorithm.RandomNOutOfNSampling;
                    case null, default -> Flow.SamplingAlgorithm.Unassigned;
                };
            }

            /**
             * What the exporter put on this record, then what it advertised in its sampler
             * options table, then what the operator configured, then an assumed 1.0. A sampling
             * exporter usually states its rate only in the options table, so without the middle
             * rung this reports 1.0 for a router sampling 1:1000.
             *
             * <p>Walked once and memoized: the interval and the rung that produced it are two
             * views of one resolution, and a second traversal would drift from the first.
             */
            private final Supplier<ResolvedRate> rate = Suppliers.memoize(() ->
                    Optionals.first(
                                    usable(raw.SAMPLING_INTERVAL),
                                    usable(raw.FLOW_SAMPLER_RANDOM_INTERVAL))
                            .map(interval -> ResolvedRate.of(interval, SamplingProvenance.Record))
                            .or(() -> Optional.ofNullable(advertised.get())
                                    .flatMap(rate -> rate)
                                    .map(AdvertisedRate::interval)
                                    .map(Netflow9FlowBuilder::usable)
                                    .map(interval -> ResolvedRate.of(interval, SamplingProvenance.Options)))
                            .or(() -> Optional.ofNullable(usable(asDouble(flowSamplingIntervalFallback)))
                                    .map(interval -> ResolvedRate.of(interval, SamplingProvenance.Fallback)))
                            .orElseGet(ResolvedRate::assumed));

            @Override
            public double getSamplingInterval() {
                return this.rate.get().interval();
            }

            @Override
            public SamplingProvenance getSamplingProvenance() {
                return this.rate.get().from();
            }
        };
    }

    /**
     * A rate counts as an answer when it is present and finite, including an explicit 1: an
     * exporter stating it does not sample has answered, and must not be overridden by a
     * receiver-wide fallback meant for a different exporter on the same port. Only absent, 0
     * (which exporters use as a placeholder) and non-finite fall through to the next rung.
     */
    private static Double usable(final Double interval) {
        return interval != null && Double.isFinite(interval) && interval >= 1.0 ? interval : null;
    }

    private static Double asDouble(final Long value) {
        return value != null ? value.doubleValue() : null;
    }

    private Stream<Netflow9RawFlow> createRawFlows(Packet packet) {
        return packet.dataSets.stream()
                .flatMap(ds -> ds.records.stream())
                .map(record -> {
                    final var dummyFlow = new Netflow9RawFlow();
                    for (var value : record.getValues()) {
                        this.conversionService.apply(value, dummyFlow);
                    }
                    dummyFlow.recordCount = packet.header.count;
                    dummyFlow.sysUpTime = Duration.ofMillis(packet.header.sysUpTime);
                    dummyFlow.unixSecs = Instant.ofEpochSecond(packet.header.unixSecs);
                    dummyFlow.sequenceNumber = packet.header.sequenceNumber;
                    return dummyFlow;
                });
    }
}
