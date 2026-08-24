/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser.ipfix;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.riptide.flows.parser.ie.values.ValueConversionService;
import org.riptide.flows.parser.data.Flow;
import org.riptide.flows.parser.data.Flow.SamplingAlgorithm;
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
import java.util.HashMap;
import java.util.Map;
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
        // Resolved lazily, and at most once per packet per distinct selector: a record carrying its
        // own rate never asks, so an exporter that always states it does not register as a
        // permanent lookup miss.
        final Map<Long, Supplier<Optional<AdvertisedRate>>> bySelector = new HashMap<>();
        return createRawFlows(packet)
                .map(rawFlow -> buildFlow(receivedAt, rawFlow, advertisedFor(identity, rawFlow, bySelector)));
    }

    /**
     * The lookup for this record's Selector, shared with every other record in the packet naming the
     * same one, and with those naming none.
     *
     * <p>Memoized per selector rather than per record so that the cost stays one lookup per packet
     * in the ordinary case. Records in a packet share a template and so almost always name the same
     * Selector or no Selector at all; a per-record supplier would multiply both the cache reads and
     * the resolved/unresolved meter marks by the record count, which would make the miss rate mean
     * one thing for selector-aware exporters and another for everything else.</p>
     *
     * <p>{@code null} keys the exporter-wide lookup, which is what a record naming no Selector
     * gets.</p>
     */
    private Supplier<Optional<AdvertisedRate>> advertisedFor(final ExporterIdentity identity,
                                                             final IpfixRawFlow rawFlow,
                                                             final Map<Long, Supplier<Optional<AdvertisedRate>>> bySelector) {
        final Long selectorId = rawFlow.selectorId != null ? rawFlow.selectorId.longValue() : null;
        return bySelector.computeIfAbsent(selectorId, id -> Suppliers.memoize(
                () -> this.samplingTable != null ? this.samplingTable.lookup(identity, id) : Optional.empty()));
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
                // Three sources, most specific first, and each tried only if the previous had
                // nothing. The deprecated pair and the selector algorithm both come off THIS
                // record; the advertised mode describes the exporter as a whole, so it goes last
                // for the same reason the advertised rate does.
                //
                // The selector switch used to sit inside the deprecated branch's map(), so it was
                // reachable only when a record carried a deprecated field that was neither 1 nor 2.
                // A record stating just selectorAlgorithm resolved to Unassigned. Lifting it out is
                // what lets such a record answer for itself instead of deferring to the exporter.
                // Inside .or(), so `advertised.get()` fires only when the record answered nothing.
                // A first draft passed it as a third argument to Optionals.first, where Java
                // evaluates every argument before the call: the lookup then ran for every flow even
                // when the record had already answered, marking a permanent miss for any exporter
                // that states its own algorithm and never sends a sampler table. Not pinned by a
                // test — the honest way to reach it is a packet whose records carry their own
                // algorithm, and no captured fixture here has one.
                return fromDeprecatedFields(rawFlow)
                        .or(() -> fromSelectorAlgorithmName(rawFlow.selectorAlgorithm))
                        .or(() -> advertised.get().map(AdvertisedRate::selectorAlgorithm)
                                .flatMap(IpFixFlowBuilder::fromSelectorAlgorithmName))
                        .or(() -> advertised.get().map(AdvertisedRate::mode)
                                .flatMap(IpFixFlowBuilder::fromDeprecatedValue))
                        .orElse(SamplingAlgorithm.Unassigned);
            }

            /*
             * What the record carries, then what the exporter's options records say — either an
             * interval it stated outright or one computed from the Selector Report for the Selector
             * this record names — then what the operator configured, then an assumed 1.0.
             *
             * <p>There is no rung between `record` and the options tables. Selector parameters used
             * to be read off the flow record and divided into a rate here, which RFC 5476 §6.5.2
             * does not describe: it requires a Selector's configuration to travel in an Options
             * Template Record scoped by selectorId, leaving only that reference on the record. The
             * rung defaulted every absent parameter to the value making its formula evaluate to
             * 1.0, and sat above the options table, so an exporter naming a bare algorithm reported
             * itself unsampled and suppressed the rate it had advertised (#584).
             *
             * <p>Walked once and memoized: the interval and the rung that produced it are two
             * views of one resolution, and a second traversal would both drift from the first and
             * defeat the laziness below.
             */
            private final Supplier<ResolvedRate> rate = Suppliers.memoize(() -> {
                // Evaluated in order and lazily: a record carrying its own rate must not trigger
                // the table lookup, so an exporter that always states one does not register as a
                // permanent lookup miss.
                final Double onRecord = Optionals.first(
                                usable(rawFlow.samplingInterval),
                                usable(rawFlow.samplerRandomInterval))
                        .orElse(null);
                if (onRecord != null) {
                    return ResolvedRate.of(onRecord, SamplingProvenance.Record);
                }
                // `options` and `derived` are the same rung, and differ only in whether riptide did
                // the arithmetic. Both describe the exporter rather than this flow, so neither
                // outranks the other; the table resolves which applies, preferring the Selector
                // this record names over the exporter-wide advertisement on specificity alone.
                final AdvertisedRate fromOptions = advertised.get().orElse(null);
                final Double stated = fromOptions != null ? usable(fromOptions.interval()) : null;
                if (stated != null) {
                    return ResolvedRate.of(stated, fromOptions.computed()
                            ? SamplingProvenance.Derived
                            : SamplingProvenance.Options);
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

    /** The deprecated IE 35 / IE 49 pair, which only ever expresses two algorithms. */
    private static Optional<SamplingAlgorithm> fromDeprecatedFields(final IpfixRawFlow rawFlow) {
        return Optionals.first(rawFlow.samplingAlgorithm, rawFlow.samplerMode)
                .flatMap(IpFixFlowBuilder::fromDeprecatedValue);
    }

    private static Optional<SamplingAlgorithm> fromDeprecatedValue(final Integer value) {
        return switch (value) {
            case 1 -> Optional.of(SamplingAlgorithm.SystematicCountBasedSampling);
            case 2 -> Optional.of(SamplingAlgorithm.RandomNOutOfNSampling);
            case null, default -> Optional.empty();
        };
    }

    /**
     * RFC 5477's selector algorithm, which names the process rather than the rate.
     *
     * <p>Takes the value rather than the record because two things carry one: the record itself,
     * and the Selector Report the exporter sent for the Selector the record names.</p>
     */
    private static Optional<SamplingAlgorithm> fromSelectorAlgorithmName(final Integer selectorAlgorithm) {
        final SamplingAlgorithm named = switch (selectorAlgorithm) {
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
        return Optional.ofNullable(named);
    }
}
