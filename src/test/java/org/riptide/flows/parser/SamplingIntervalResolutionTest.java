/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser;

import com.codahale.metrics.MetricRegistry;
import org.junit.jupiter.api.Test;
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
 * in its sampler options table, then what the operator configured, then unsampled.
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

    private static double v9Interval(final Netflow9RawFlow raw, final Double advertised, final Long fallback) {
        final AdvertisedRate rate = advertised != null ? new AdvertisedRate(advertised, null) : null;
        final var builder = new Netflow9FlowBuilder(NETFLOW9);
        builder.setFlowSamplingIntervalFallback(fallback);
        return builder.buildFlow(Instant.EPOCH, raw, rate).getSamplingInterval();
    }

    @Test
    void netflow9PrefersTheRateOnTheRecord() {
        final var raw = v9Raw();
        raw.SAMPLING_INTERVAL = 512.0;

        assertThat(v9Interval(raw, 1000.0, 2000L)).isEqualTo(512.0);
    }

    /** The ASR9k sampler table names the rate field 50, so the record form of it counts too. */
    @Test
    void netflow9ReadsTheSamplerRandomIntervalOnTheRecord() {
        final var raw = v9Raw();
        raw.FLOW_SAMPLER_RANDOM_INTERVAL = 256.0;

        assertThat(v9Interval(raw, 1000.0, 2000L)).isEqualTo(256.0);
    }

    /** The case this change exists for: nothing on the record, a rate in the options table. */
    @Test
    void netflow9FallsBackToTheAdvertisedRate() {
        assertThat(v9Interval(v9Raw(), 1000.0, 2000L)).isEqualTo(1000.0);
    }

    @Test
    void netflow9FallsBackToConfigurationWhenTheExporterSaysNothing() {
        assertThat(v9Interval(v9Raw(), null, 2000L)).isEqualTo(2000.0);
    }

    @Test
    void netflow9ReportsUnsampledWhenNothingIsKnown() {
        assertThat(v9Interval(v9Raw(), null, null)).isEqualTo(1.0);
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

    /** 0 is a placeholder rather than an answer, so it falls through. */
    @Test
    void netflow9TreatsAZeroRecordRateAsNoAnswer() {
        final var raw = v9Raw();
        raw.SAMPLING_INTERVAL = 0.0;

        assertThat(v9Interval(raw, null, 2000L)).isEqualTo(2000.0);
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

    private static double ipfixInterval(final IpfixRawFlow raw, final Long fallback) {
        final var builder = new IpFixFlowBuilder(IPFIX);
        builder.setFlowSamplingIntervalFallback(fallback);
        return builder.buildFlow(Instant.EPOCH, raw).getSamplingInterval();
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

        assertThat(ipfixInterval(raw, 2000L)).isEqualTo(512.0);
    }

    @Test
    void ipfixFallsBackToConfiguration() {
        assertThat(ipfixInterval(new IpfixRawFlow(), 2000L)).isEqualTo(2000.0);
    }

}
