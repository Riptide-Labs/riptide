/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser.ipfix;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.primitives.UnsignedLong;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.riptide.flows.parser.ie.values.ValueConversionService;
import org.riptide.flows.parser.data.Flow;
import org.riptide.flows.parser.data.Flow.SamplingProvenance;
import org.riptide.flows.parser.data.Optionals;
import org.riptide.flows.parser.data.ResolvedRate;
import org.riptide.flows.parser.data.Timeout;
import org.riptide.flows.parser.ipfix.proto.Packet;
import org.riptide.flows.parser.session.ExporterSamplingTable;
import org.riptide.flows.parser.session.ExporterSamplingTable.AdvertisedRate;
import org.riptide.pipeline.ExporterIdentity;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
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

    /** Rates exporters advertise in sampler options records; null until a parser supplies one. */
    @Setter
    private ExporterSamplingTable samplingTable;

    public IpFixFlowBuilder(final ValueConversionService conversionService) {
        this.conversionService = Objects.requireNonNull(conversionService);
    }

    public Stream<Flow> buildFlows(final Instant receivedAt,
                                   final Packet packet) {
        return buildFlows(receivedAt, packet, null);
    }

    /**
     * The exporter identity is threaded in rather than held on the builder, for the same reason it
     * is in {@code Netflow9FlowBuilder}: one UDP parser fronts every exporter sending to its port,
     * so a builder-scoped rate would be handed to whichever exporter happened to send next.
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
                          final IpfixRawFlow rawFlow) {
        return buildFlow(receivedAt, rawFlow, Optional::empty);
    }

    public Flow buildFlow(final Instant receivedAt,
                          final IpfixRawFlow rawFlow,
                          final AdvertisedRate advertisedRate) {
        return buildFlow(receivedAt, rawFlow, () -> Optional.ofNullable(advertisedRate));
    }

    private Flow buildFlow(final Instant receivedAt,
                           final IpfixRawFlow rawFlow,
                           final Supplier<Optional<AdvertisedRate>> advertised) {
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
                // The total counters come last: some exporters (e.g. Juniper SRX inline J-Flow)
                // send only octetTotalCount/packetTotalCount, never the delta variants. Like
                // NetGauze and nfdump we treat the total as the record's count — a knowing
                // approximation that over-counts a long-lived flow if its exporter re-reports
                // growing totals at each active timeout.
                return Optionals.first(
                                rawFlow.octetDeltaCount,
                                rawFlow.postOctetDeltaCount,
                                rawFlow.layer2OctetDeltaCount,
                                rawFlow.postLayer2OctetDeltaCount,
                                rawFlow.transportOctetDeltaCount,
                                rawFlow.octetTotalCount,
                                rawFlow.postOctetTotalCount,
                                rawFlow.layer2OctetTotalCount,
                                rawFlow.postLayer2OctetTotalCount)
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
                            // No flow-start element exported: fall back to the export time (the
                            // packet header timestamp), as goflow2 does — honouring the non-null Flow
                            // contract and the non-nullable column.
                            return this.getTimestamp();
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
                            // No flow-end element exported: fall back to the export time (see getFirstSwitched).
                            return this.getTimestamp();
                        });
            }

            @Override
            public Instant getDeltaSwitched() {
                final var flowActiveTimeout = Optionals.first(rawFlow.flowActiveTimeout, flowActiveTimeoutFallback).orElse(null);
                final var flowInactiveTimeout = Optionals.first(rawFlow.flowInactiveTimeout, flowInactiveTimeoutFallback).orElse(null);

                final var delta = new Timeout()
                        .withActiveTimeout(flowActiveTimeout)
                        .withInactiveTimeout(flowInactiveTimeout)
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
                return Optionals.first(rawFlow.packetDeltaCount, rawFlow.postPacketDeltaCount, rawFlow.transportPacketDeltaCount, rawFlow.packetTotalCount, rawFlow.postPacketTotalCount).orElse(0L);
            }

            @Override
            public int getProtocol() {
                return Optionals.first(rawFlow.protocolIdentifier).orElse(0);
            }

            @Override
            public SamplingAlgorithm getSamplingAlgorithm() {
                return Optionals.first(rawFlow.samplingAlgorithm, rawFlow.samplerMode,
                                advertised.get().map(AdvertisedRate::mode).orElse(null))
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

            /*
             * What the record carries, then what the selector algorithm implies, then what the
             * operator configured, then an assumed 1.0. Algorithms 0, 8 and 9 have no expressible
             * interval and yield NaN, which is honest but would land in a Float64 column and
             * poison any aggregate multiplying by it — so it falls through as unknown instead.
             *
             * <p>Walked once and memoized: the interval and the rung that produced it are two
             * views of one resolution, and a second traversal would both drift from the first and
             * defeat the laziness below.
             */
            private final Supplier<ResolvedRate> rate = Suppliers.memoize(() -> {
                // Evaluated in order and lazily: the selector-algorithm derivation divides by
                // exporter-supplied ranges, so it must not run for a record that already carries
                // its rate — a degenerate range would then cost the whole packet's batch.
                final Double onRecord = Optionals.first(
                                usable(rawFlow.samplingInterval),
                                usable(rawFlow.samplerRandomInterval))
                        .orElse(null);
                if (onRecord != null) {
                    return ResolvedRate.of(onRecord, SamplingProvenance.Record);
                }
                // Above `derived` deliberately: a rate the exporter STATES outranks one riptide
                // computes from its selector parameters, which is why the ladder calls derived the
                // rung carrying the least authority. Below `record` for the reason v9 has it there
                // — a rate on the flow record is more specific than one advertised for the exporter.
                final Double stated = advertised.get()
                        .map(AdvertisedRate::interval)
                        .map(IpFixFlowBuilder::usable)
                        .orElse(null);
                if (stated != null) {
                    return ResolvedRate.of(stated, SamplingProvenance.Options);
                }
                final Double derived = usable(fromSelectorAlgorithm());
                if (derived != null) {
                    return ResolvedRate.of(derived, SamplingProvenance.Derived);
                }
                final Double configured = usable(asDouble(flowSamplingIntervalFallback));
                if (configured != null) {
                    return ResolvedRate.of(configured, SamplingProvenance.Fallback);
                }
                return ResolvedRate.assumed();
            });

            @Override
            public double getSamplingInterval() {
                return this.rate.get().interval();
            }

            @Override
            public SamplingProvenance getSamplingProvenance() {
                return this.rate.get().from();
            }

            /* RFC 5477 selector algorithms, as an interval where one is expressible. */
            private Double fromSelectorAlgorithm() {
                return switch (rawFlow.selectorAlgorithm) {
                    case 0, 8, 9 -> {
                        yield Double.NaN;
                    }
                    case 1, 2 -> {
                        final var interval = Optionals.first(rawFlow.samplingFlowInterval, rawFlow.flowSamplingTimeInterval).orElse(1.0);
                        final var spacing = Optionals.first(rawFlow.samplingFlowSpacing, rawFlow.flowSamplingTimeSpacing).orElse(0.0);
                        yield interval + spacing / interval;
                    }
                    case 3 -> {
                        final var size = Optionals.of(rawFlow.samplingSize).orElse(1.0);
                        final var population = Optionals.of(rawFlow.samplingPopulation).orElse(1.0);
                        yield population / size;
                    }
                    case 4 -> {
                        final var probability = Optionals.of(rawFlow.samplingProbability).orElse(1.0);
                        yield 1.0 / probability;
                    }
                    case 5, 6, 7 -> {
                        final var selectedRangeMin = Optionals.of(rawFlow.hashSelectedRangeMin).orElse(UnsignedLong.ZERO);
                        final var selectedRangeMax = Optionals.of(rawFlow.hashSelectedRangeMax).orElse(UnsignedLong.MAX_VALUE);
                        final var outputRangeMin = Optionals.of(rawFlow.hashOutputRangeMin).orElse(UnsignedLong.ZERO);
                        final var outputRangeMax = Optionals.of(rawFlow.hashOutputRangeMax).orElse(UnsignedLong.MAX_VALUE);
                        final var selectedRange = selectedRangeMax.minus(selectedRangeMin);
                        // An exporter is free to send a degenerate range; dividing by it
                        // would throw and cost the whole packet, so treat it as unknown.
                        if (selectedRange.equals(UnsignedLong.ZERO)) {
                            yield null;
                        }
                        yield outputRangeMax.minus(outputRangeMin).dividedBy(selectedRange).doubleValue();
                    }
                    case null, default -> {
                        // No algorithm, or one this does not model: nothing was
                        // derived, so fall through rather than assert "not sampled" —
                        // that would outrank a configured fallback with a guess.
                        yield null;
                    }
                };
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

    /**
     * A rate counts as an answer when it is present and finite, including an explicit 1: an
     * exporter stating it does not sample has answered, and must not be overridden by a
     * receiver-wide fallback meant for a different exporter. Absent, 0 (a placeholder) and
     * non-finite — which is what an inexpressible selector algorithm yields — fall through.
     */
    private static Double usable(final Double interval) {
        return interval != null && Double.isFinite(interval) && interval >= 1.0 ? interval : null;
    }

    private static Double asDouble(final Long value) {
        return value != null ? value.doubleValue() : null;
    }

    private Stream<IpfixRawFlow> createRawFlows(final Packet packet) {
        final int recordCount = packet.dataRecordCount();
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
