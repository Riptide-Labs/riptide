/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser.netflow5;


import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import org.riptide.flows.parser.data.Flow;
import org.riptide.flows.parser.data.Flow.SamplingProvenance;
import org.riptide.flows.parser.data.ResolvedRate;
import org.riptide.flows.parser.netflow5.proto.Header;
import org.riptide.flows.parser.netflow5.proto.Packet;
import org.riptide.flows.parser.netflow5.proto.Record;

import java.net.InetAddress;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

public final class Netflow5FlowBuilder {

    /**
     * The rate an operator declared for this receiver, {@code null} when none is configured.
     *
     * <p>NetFlow v5 has no options-template mechanism, so unlike v9 there is no rung between the
     * exporter and this one: whatever the exporter cannot say, only configuration can.
     */
    private Long flowSamplingIntervalFallback;

    /**
     * Whether an interval is read as a rate when the header's algorithm bits are not 1 or 2.
     *
     * <p>Governs that case alone. A header stating algorithm 1 or 2 <em>together with a non-zero
     * interval</em> is unambiguous and always read: the exporter said both how and how much, and
     * discarding the interval would leave {@code flowSamplingIntervalFallback} overriding a rate
     * the exporter explicitly gave.
     *
     * <p>Note the qualifier. A stated mode with a zero interval is not a rate, so it still falls
     * through to the configured fallback — the exporter named a method and no number, and only
     * configuration can supply the number.
     */
    private boolean trustHeaderSamplingInterval = true;

    public void setFlowSamplingIntervalFallback(final Long flowSamplingIntervalFallback) {
        this.flowSamplingIntervalFallback = flowSamplingIntervalFallback;
    }

    public void setTrustHeaderSamplingInterval(final boolean trustHeaderSamplingInterval) {
        this.trustHeaderSamplingInterval = trustHeaderSamplingInterval;
    }

    /**
     * Which rung answered, counted per packet and scoped to the receiver.
     *
     * <p>Reading the header changes recorded rates without changing anything in the data path —
     * riptide stores the rate and does not apply it — so nothing else would show an operator that
     * their exporters are now being read differently. Per packet rather than per flow because the
     * rate is a property of the header: every record in a packet resolves identically, and counting
     * each one would report the same fact once per flow.
     *
     * <p>Named per receiver like every other parser metric, rather than globally as
     * {@code ExporterSamplingTable} does. That table is one Spring bean for the whole daemon; this
     * builder is one per parser, so an unscoped name would blend a dedicated v5 receiver with the
     * v5 half of a {@code multi} receiver and hide which one stopped resolving from the header.
     *
     * <p>Keyed by the rung itself and named from its token, so the metric and the
     * {@code samplingProvenance} column cannot drift apart: renaming a rung renames both. Only the
     * three rungs a v5 packet can reach are registered — the other three would be permanently zero
     * and would read as "this receiver never resolves that way" rather than "it cannot".
     */
    private final Map<SamplingProvenance, Meter> rungMeters;

    /** The rungs a v5 packet can resolve through. There is no options table and no record rung. */
    private static final List<SamplingProvenance> RUNGS =
            List.of(SamplingProvenance.Header, SamplingProvenance.Fallback, SamplingProvenance.Assumed);

    public Netflow5FlowBuilder(final String name, final MetricRegistry metrics) {
        final var meters = new EnumMap<SamplingProvenance, Meter>(SamplingProvenance.class);
        for (final var rung : RUNGS) {
            meters.put(rung, metrics.meter(MetricRegistry.name("parsers", name, "samplingRate", rung.token())));
        }
        this.rungMeters = Collections.unmodifiableMap(meters);
    }

    public Stream<Flow> buildFlows(final Instant receivedAt, final Packet packet) {
        // Resolved once for the packet: the rate lives in the header, so it is the same for every
        // record, and resolving per flow would recompute it and over-count the meters.
        final ResolvedRate rate = resolveSamplingRate(packet.header);
        // Fails loudly rather than with a bare NPE if a rung is ever added to the ladder below
        // without being registered above.
        Objects.requireNonNull(this.rungMeters.get(rate.from()),
                () -> "no meter registered for sampling rung " + rate.from()).mark();
        return packet.records.stream()
                .map(record -> buildFlow(receivedAt, packet.header, record, rate));
    }

    /**
     * What the exporter put in the packet header, then what the operator configured, then an
     * assumed 1.0. NetFlow v5 has no options-template mechanism, so the header is the only thing the
     * exporter itself can say and there is no rung between it and configuration.
     *
     * <p>The header packs a 2-bit algorithm and a 14-bit interval into one word, and the two
     * non-zero cases are not equally clear. Algorithm 1 or 2 means the exporter stated both how and
     * how much, so the interval is read unconditionally — suppressing it would leave a configured
     * fallback overriding a rate the exporter explicitly gave. Algorithm 0 with a non-zero interval
     * is self-contradictory on its face, yet it is what a sampling exporter routinely emits: the
     * mode bits are not a mandatory field, and pmacct's own exporter never sets them. Reading it is
     * the default and what every comparable collector does, but it is the one case an operator can
     * pin off with {@code trust-header-sampling-interval}.
     *
     * <p>An interval of 0 is the signal that an exporter does not sample. The mode bits are not.
     *
     * <p>Only 1 and 2 count as a signalled mode. Algorithm 3 is undefined in the v5 header, so it
     * carries no more meaning than 0 does and must not buy the unconditional branch: a word of all
     * ones would otherwise be read as a rate of 16383 that the operator has no way to suppress.
     * {@code getSamplingAlgorithm} maps 3 to {@code Unassigned} for the same reason.
     */
    private ResolvedRate resolveSamplingRate(final Header header) {
        final Double advertised = usable((double) header.samplingInterval);
        final boolean modeSignalled = header.samplingAlgorithm == 1 || header.samplingAlgorithm == 2;
        if (advertised != null && (modeSignalled || this.trustHeaderSamplingInterval)) {
            return ResolvedRate.of(advertised, SamplingProvenance.Header);
        }
        final Double configured = usable(asDouble(this.flowSamplingIntervalFallback));
        if (configured != null) {
            return ResolvedRate.of(configured, SamplingProvenance.Fallback);
        }
        return ResolvedRate.assumed();
    }

    private Flow buildFlow(final Instant receivedAt,
                           final Header header,
                           final Record record,
                           final ResolvedRate rate) {

        final var timestamp = Instant.ofEpochSecond(header.unixSecs, header.unixNSecs);
        final var bootTime = timestamp.minus(header.sysUptime, ChronoUnit.MILLIS);

        return new Flow() {
            @Override
            public Instant getReceivedAt() {
                return receivedAt;
            }

            @Override
            public Instant getTimestamp() {
                return timestamp;
            }

            @Override
            public Flow.FlowProtocol getFlowProtocol() {
                return Flow.FlowProtocol.NetflowV5;
            }

            @Override
            public int getFlowRecords() {
                return header.count;
            }

            @Override
            public long getFlowSeqNum() {
                return header.flowSequence;
            }

            @Override
            public Instant getFirstSwitched() {
                return bootTime.plus(record.first, ChronoUnit.MILLIS);
            }

            @Override
            public Instant getLastSwitched() {
                return bootTime.plus(record.last, ChronoUnit.MILLIS);
            }

            @Override
            public int getInputSnmp() {
                return record.input;
            }

            @Override
            public int getOutputSnmp() {
                return record.output;
            }

            @Override
            public long getSrcAs() {
                return record.srcAs;
            }

            @Override
            public InetAddress getSrcAddr() {
                return record.srcAddr;
            }

            @Override
            public int getSrcMaskLen() {
                return record.srcMask;
            }

            @Override
            public int getSrcPort() {
                return record.srcPort;
            }

            @Override
            public long getDstAs() {
                return record.dstAs;
            }

            @Override
            public InetAddress getDstAddr() {
                return record.dstAddr;
            }

            @Override
            public int getDstMaskLen() {
                return record.dstMask;
            }

            @Override
            public int getDstPort() {
                return record.dstPort;
            }

            @Override
            public InetAddress getNextHop() {
                return record.nextHop;
            }

            @Override
            public long getBytes() {
                return record.dOctets;
            }

            @Override
            public long getPackets() {
                return record.dPkts;
            }

            @Override
            public Flow.Direction getDirection() {
                return record.egress
                        ? Flow.Direction.EGRESS
                        : Flow.Direction.INGRESS;
            }

            @Override
            public int getEngineId() {
                return header.engineId;
            }

            @Override
            public int getEngineType() {
                return header.engineType;
            }

            @Override
            public int getVlan() {
                return 0;
            }

            @Override
            public int getIpProtocolVersion() {
                return 4;
            }

            @Override
            public int getProtocol() {
                return record.proto;
            }

            @Override
            public int getTcpFlags() {
                return record.tcpFlags;
            }

            @Override
            public int getTos() {
                return record.tos;
            }

            @Override
            public Flow.SamplingAlgorithm getSamplingAlgorithm() {
                return switch (header.samplingAlgorithm) {
                    case 1 -> Flow.SamplingAlgorithm.SystematicCountBasedSampling;
                    case 2 -> Flow.SamplingAlgorithm.RandomNOutOfNSampling;
                    default -> Flow.SamplingAlgorithm.Unassigned;
                };
            }

            /** Resolved once for the packet; see {@code resolveSamplingRate}. */
            @Override
            public double getSamplingInterval() {
                return rate.interval();
            }

            @Override
            public SamplingProvenance getSamplingProvenance() {
                return rate.from();
            }
        };
    }

    /**
     * A rate counts as an answer when it is present and finite, including an explicit 1: an
     * operator configuring 1 has stated this receiver's exporters do not sample. Only absent,
     * 0 and non-finite fall through to the next rung. Mirrors {@code Netflow9FlowBuilder}.
     */
    private static Double usable(final Double interval) {
        return interval != null && Double.isFinite(interval) && interval >= 1.0 ? interval : null;
    }

    private static Double asDouble(final Long value) {
        return value != null ? value.doubleValue() : null;
    }
}
