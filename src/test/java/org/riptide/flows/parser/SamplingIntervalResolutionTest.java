/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser;

import com.codahale.metrics.MetricRegistry;
import com.google.common.primitives.UnsignedLong;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.riptide.flows.parser.data.Flow;
import org.riptide.flows.parser.data.Flow.SamplingProvenance;
import org.riptide.flows.parser.ie.Value;
import org.riptide.flows.parser.ie.values.UnsignedValue;
import org.riptide.flows.parser.ie.values.ValueConversionService;
import org.riptide.flows.parser.ie.values.visitor.BooleanVisitor;
import org.riptide.flows.parser.ie.values.visitor.DoubleVisitor;
import org.riptide.flows.parser.ie.values.visitor.DurationVisitor;
import org.riptide.flows.parser.ie.values.visitor.InetAddressVisitor;
import org.riptide.flows.parser.ie.values.visitor.InstantVisitor;
import org.riptide.flows.parser.ie.values.visitor.IntegerVisitor;
import org.riptide.flows.parser.ie.values.visitor.LongVisitor;
import org.riptide.flows.parser.ie.values.visitor.StringVisitor;
import org.riptide.flows.parser.ie.values.visitor.UnsignedLongVisitor;
import org.riptide.flows.parser.ie.values.visitor.ValueVisitor;
import org.riptide.flows.parser.ipfix.IpFixFlowBuilder;
import org.riptide.flows.parser.ipfix.IpfixRawFlow;
import org.riptide.flows.parser.netflow9.Netflow9FlowBuilder;
import org.riptide.flows.parser.netflow9.Netflow9RawFlow;
import org.riptide.flows.parser.sflow.proto.Datagram;
import org.riptide.flows.parser.session.ExporterSamplingTable;
import org.riptide.flows.parser.session.ExporterSamplingTable.AdvertisedRate;
import org.riptide.pipeline.ExporterIdentity;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sampling-rate resolution ladder: what the record carries, then what the exporter advertised
 * in its sampler options table, then what the operator configured, then an assumed 1.0 — and,
 * for each, the provenance recorded alongside the value.
 *
 * <p>Built by hand rather than through Spring, following the fuzz harness.
 */
class SamplingIntervalResolutionTest {

    private static final List<ValueVisitor<?>> VISITORS = List.of(
            new BooleanVisitor(), new DoubleVisitor(), new DurationVisitor(), new InetAddressVisitor(),
            new InstantVisitor(), new IntegerVisitor(), new LongVisitor(), new StringVisitor(),
            new UnsignedLongVisitor());

    private static final ValueConversionService NETFLOW9 =
            new ValueConversionService(Netflow9RawFlow.class, VISITORS);
    private static final ValueConversionService IPFIX =
            new ValueConversionService(IpfixRawFlow.class, VISITORS);

    // ---- NetFlow v9 -------------------------------------------------------------------------

    private static Netflow9RawFlow v9Raw() {
        final var raw = new Netflow9RawFlow();
        raw.unixSecs = Instant.EPOCH;
        raw.sysUpTime = Duration.ZERO;
        return raw;
    }

    private static Flow v9Flow(final Netflow9RawFlow raw, final Double advertised, final Long fallback) {
        final AdvertisedRate rate = advertised != null ? new AdvertisedRate(advertised, null) : null;
        final var builder = new Netflow9FlowBuilder(NETFLOW9);
        builder.setFlowSamplingIntervalFallback(fallback);
        return builder.buildFlow(Instant.EPOCH, raw, rate);
    }

    private static double v9Interval(final Netflow9RawFlow raw, final Double advertised, final Long fallback) {
        return v9Flow(raw, advertised, fallback).getSamplingInterval();
    }

    /**
     * The interval and its provenance come off one flow, not two builds: they are two views of a
     * single resolution, and asserting them together is what pins that they cannot disagree.
     */
    private static void assertResolved(final Flow flow, final double interval, final SamplingProvenance from) {
        assertThat(flow.getSamplingInterval()).isEqualTo(interval);
        assertThat(flow.getSamplingProvenance()).isEqualTo(from);
    }

    @Test
    void netflow9PrefersTheRateOnTheRecord() {
        final var raw = v9Raw();
        raw.SAMPLING_INTERVAL = 512.0;

        assertResolved(v9Flow(raw, 1000.0, 2000L), 512.0, SamplingProvenance.Record);
    }

    /** The ASR9k sampler table names the rate field 50, so the record form of it counts too. */
    @Test
    void netflow9ReadsTheSamplerRandomIntervalOnTheRecord() {
        final var raw = v9Raw();
        raw.FLOW_SAMPLER_RANDOM_INTERVAL = 256.0;

        assertResolved(v9Flow(raw, 1000.0, 2000L), 256.0, SamplingProvenance.Record);
    }

    /** The case this change exists for: nothing on the record, a rate in the options table. */
    @Test
    void netflow9FallsBackToTheAdvertisedRate() {
        assertResolved(v9Flow(v9Raw(), 1000.0, 2000L), 1000.0, SamplingProvenance.Options);
    }

    @Test
    void netflow9FallsBackToConfigurationWhenTheExporterSaysNothing() {
        assertResolved(v9Flow(v9Raw(), null, 2000L), 2000.0, SamplingProvenance.Fallback);
    }

    @Test
    void netflow9AssumesUnsampledWhenNothingIsKnown() {
        assertResolved(v9Flow(v9Raw(), null, null), 1.0, SamplingProvenance.Assumed);
    }

    /**
     * An exporter stating 1 has answered "I do not sample", and that answer must stand. One
     * receiver fronts every exporter on its port while the fallback is per-receiver, so letting a
     * fallback meant for a silent router override an honest 1 would inflate this exporter by the
     * whole fallback factor.
     */
    @Test
    void netflow9HonoursAnExplicitlyUnsampledRecordOverTheFallback() {
        final var raw = v9Raw();
        raw.SAMPLING_INTERVAL = 1.0;

        assertThat(v9Interval(raw, null, 2000L)).isEqualTo(1.0);
    }

    /**
     * The distinction the column exists for. Both flows report 1.0; only the provenance separates
     * an exporter that said "I do not sample" from one that said nothing at all.
     */
    @Test
    void netflow9DistinguishesAStatedRateOfOneFromAnAssumedOne() {
        final var stated = v9Raw();
        stated.SAMPLING_INTERVAL = 1.0;

        assertResolved(v9Flow(stated, null, null), 1.0, SamplingProvenance.Record);
        assertResolved(v9Flow(v9Raw(), null, null), 1.0, SamplingProvenance.Assumed);
    }

    /** An options table advertising 1 is an answer too, and names its own rung. */
    @Test
    void netflow9AttributesAnAdvertisedRateOfOneToTheOptionsTable() {
        assertResolved(v9Flow(v9Raw(), 1.0, null), 1.0, SamplingProvenance.Options);
    }

    /** 0 is a placeholder rather than an answer, so it falls through. */
    @Test
    void netflow9TreatsAZeroRecordRateAsNoAnswer() {
        final var raw = v9Raw();
        raw.SAMPLING_INTERVAL = 0.0;

        assertResolved(v9Flow(raw, null, 2000L), 2000.0, SamplingProvenance.Fallback);
    }

    /** The join this change exists for: a rate in the table reaches the flow the builder makes. */
    @Test
    void netflow9ResolvesTheLearnedRateThroughTheSamplingTable() throws Exception {
        final var table = new ExporterSamplingTable(new MetricRegistry());
        final var exporter = new ExporterIdentity.NetflowIpfix(InetAddress.getByName("192.0.2.1"), 0);
        final var other = new ExporterIdentity.NetflowIpfix(InetAddress.getByName("192.0.2.2"), 0);
        table.accept(exporter, List.of(), List.<Value<?>>of(
                new UnsignedValue("FLOW_SAMPLER_RANDOM_INTERVAL", 1000),
                new UnsignedValue("FLOW_SAMPLER_MODE", 2)));

        final var builder = new Netflow9FlowBuilder(NETFLOW9);
        builder.setSamplingTable(table);

        assertThat(builder.buildFlow(Instant.EPOCH, v9Raw(), table.lookup(exporter).orElse(null))
                .getSamplingInterval()).isEqualTo(1000.0);
        assertThat(builder.buildFlow(Instant.EPOCH, v9Raw(), table.lookup(other).orElse(null))
                .getSamplingInterval()).isEqualTo(1.0);
        // the mode travels with it, so the pair is not self-contradictory
        assertThat(builder.buildFlow(Instant.EPOCH, v9Raw(), table.lookup(exporter).orElse(null))
                .getSamplingAlgorithm())
                .isEqualTo(org.riptide.flows.parser.data.Flow.SamplingAlgorithm.RandomNOutOfNSampling);
    }

    /**
     * The non-goal, pinned: resolving a rate records metadata and nothing else. If volume
     * correction is ever added it will be a deliberate change to this assertion, not a side effect.
     */
    @Test
    void learningARateLeavesVolumeCountersUntouched() {
        final var raw = v9Raw();
        raw.IN_BYTES = 1_500L;
        raw.IN_PKTS = 10L;

        final var unsampled = new Netflow9FlowBuilder(NETFLOW9).buildFlow(Instant.EPOCH, raw, (AdvertisedRate) null);
        final var sampled = new Netflow9FlowBuilder(NETFLOW9)
                .buildFlow(Instant.EPOCH, raw, new AdvertisedRate(1000.0, null));

        assertThat(sampled.getSamplingInterval()).isEqualTo(1000.0);
        assertThat(unsampled.getSamplingInterval()).isEqualTo(1.0);
        // only the metadata differs
        assertThat(sampled.getBytes()).isEqualTo(unsampled.getBytes()).isEqualTo(1_500L);
        assertThat(sampled.getPackets()).isEqualTo(unsampled.getPackets()).isEqualTo(10L);
    }

    // ---- IPFIX ------------------------------------------------------------------------------

    private static Flow ipfixFlow(final IpfixRawFlow raw, final Long fallback) {
        final var builder = new IpFixFlowBuilder(IPFIX);
        builder.setFlowSamplingIntervalFallback(fallback);
        return builder.buildFlow(Instant.EPOCH, raw);
    }

    private static double ipfixInterval(final IpfixRawFlow raw, final Long fallback) {
        return ipfixFlow(raw, fallback).getSamplingInterval();
    }

    /**
     * Selector algorithms 0, 8 and 9 have no expressible interval. Before this change that reached
     * the Float64 column as NaN, which would poison any aggregate multiplying by it.
     */
    @Test
    void ipfixInexpressibleSelectorAlgorithmsFallThroughToConfiguration() {
        for (final int algorithm : new int[]{0, 8, 9}) {
            final var raw = new IpfixRawFlow();
            raw.selectorAlgorithm = algorithm;

            assertThat(ipfixInterval(raw, 2000L))
                    .describedAs("selectorAlgorithm %d", algorithm)
                    .isEqualTo(2000.0);
        }
    }

    @Test
    void ipfixInexpressibleSelectorAlgorithmsNeverYieldNaN() {
        for (final int algorithm : new int[]{0, 8, 9}) {
            final var raw = new IpfixRawFlow();
            raw.selectorAlgorithm = algorithm;

            final double interval = ipfixInterval(raw, null);

            assertThat(Double.isNaN(interval))
                    .describedAs("selectorAlgorithm %d produced NaN", algorithm)
                    .isFalse();
            assertThat(interval).isEqualTo(1.0);
        }
    }

    @Test
    void ipfixPrefersTheRateOnTheRecord() {
        final var raw = new IpfixRawFlow();
        raw.samplingInterval = 512.0;

        assertResolved(ipfixFlow(raw, 2000L), 512.0, SamplingProvenance.Record);
    }

    @Test
    void ipfixFallsBackToConfiguration() {
        assertResolved(ipfixFlow(new IpfixRawFlow(), 2000L), 2000.0, SamplingProvenance.Fallback);
    }

    @Test
    void ipfixAssumesUnsampledWhenNothingIsKnown() {
        assertResolved(ipfixFlow(new IpfixRawFlow(), null), 1.0, SamplingProvenance.Assumed);
    }

    /**
     * A rate riptide computed is not a rate the exporter stated, and the column says which. This is
     * the rung that carries the least authority, so conflating it with {@code record} would be the
     * most misleading of the six.
     */
    @Test
    void ipfixNamesAComputedRateAsDerived() {
        final var raw = new IpfixRawFlow();
        raw.selectorAlgorithm = 3;
        raw.samplingSize = 1.0;
        raw.samplingPopulation = 1000.0;

        assertResolved(ipfixFlow(raw, 2000L), 1000.0, SamplingProvenance.Derived);
    }

    /**
     * The laziness the builder's own comment demands, pinned. A record carrying its own rate must
     * not evaluate the selector-algorithm rung: that rung divides by exporter-supplied ranges, and
     * a degenerate one would cost the whole packet's batch. The degenerate range here would be
     * reached only if the rung ran — reading provenance as well as the interval must not run it
     * either, which is the property the memoized single traversal buys.
     */
    @Test
    void ipfixDoesNotDeriveWhenTheRecordCarriesItsOwnRate() {
        final var raw = new IpfixRawFlow();
        raw.samplingInterval = 512.0;
        raw.selectorAlgorithm = 5;
        raw.hashSelectedRangeMin = UnsignedLong.ZERO;
        raw.hashSelectedRangeMax = UnsignedLong.ZERO;

        final var flow = ipfixFlow(raw, null);

        assertResolved(flow, 512.0, SamplingProvenance.Record);
        // reading either accessor again must not walk the ladder a second time
        assertResolved(flow, 512.0, SamplingProvenance.Record);
    }

    // ---- sFlow ------------------------------------------------------------------------------

    /** A v5 datagram carrying one compact flow sample at the given rate and no flow records. */
    private static Datagram sflowDatagram(final long samplingRate) throws Exception {
        final var sample = Unpooled.buffer()
                .writeInt(1)                        // sequence
                .writeInt(0)                        // source_id
                .writeInt((int) samplingRate)
                .writeInt(0)                        // sample pool
                .writeInt(0)                        // drops
                .writeInt(0)                        // input interface
                .writeInt(0)                        // output interface
                .writeInt(0);                       // flow record count
        final var datagram = Unpooled.buffer()
                .writeInt(Datagram.VERSION)
                .writeInt(1)                        // agent address type: IPv4
                .writeBytes(InetAddress.getByName("192.0.2.1").getAddress())
                .writeInt(0)                        // sub-agent id
                .writeInt(1)                        // sequence
                .writeInt(0)                        // uptime
                .writeInt(1)                        // sample count
                .writeInt(1)                        // sample type: flow sample
                .writeInt(sample.readableBytes())
                .writeBytes(sample);
        return new Datagram(datagram);
    }

    /** sFlow carries the rate on the sample by construction: one rung, always {@code record}. */
    @Test
    void sflowAttributesTheRateToTheSample() throws Exception {
        final var flow = sflowDatagram(1024).buildFlows(Instant.EPOCH).findFirst().orElseThrow();

        assertResolved(flow, 1024.0, SamplingProvenance.Record);
    }
}
