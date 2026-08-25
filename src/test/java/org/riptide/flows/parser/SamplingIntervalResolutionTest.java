/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser;

import com.codahale.metrics.MetricRegistry;
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
     * No selector algorithm on a record derives a rate, for any of the ten values.
     *
     * <p>This replaces a pair of tests asserting that only 0, 8 and 9 fell through. Those passed
     * both before and after the rung was removed — once nothing derives from a record, every value
     * falls through and the assertion stops distinguishing anything — so they are restated here as
     * the property that actually holds, across the whole registry rather than three of it.</p>
     *
     * <p>RFC 5476 §6.5.2 puts a Selector's parameters in an options record scoped by
     * {@code selectorId}, so a record naming an algorithm has stated a reference and nothing more.
     * Deriving from it defaulted every absent parameter into a fabricated {@code 1.0} that then
     * outranked the exporter's own advertisement (#584).</p>
     */
    @Test
    void ipfixNoSelectorAlgorithmOnARecordDerivesARate() {
        for (int algorithm = 0; algorithm <= 9; algorithm++) {
            final var raw = new IpfixRawFlow();
            raw.selectorAlgorithm = algorithm;

            assertThat(ipfixFlow(raw, 2000L))
                    .describedAs("selectorAlgorithm %d", algorithm)
                    .satisfies(flow -> {
                        assertThat(flow.getSamplingInterval()).isEqualTo(2000.0);
                        assertThat(flow.getSamplingProvenance()).isEqualTo(SamplingProvenance.Fallback);
                    });
        }
    }

    /** And with nothing configured either, the honest answer is `assumed`, never NaN. */
    @Test
    void ipfixASelectorAlgorithmAloneNeverYieldsNaN() {
        for (int algorithm = 0; algorithm <= 9; algorithm++) {
            final var raw = new IpfixRawFlow();
            raw.selectorAlgorithm = algorithm;

            final double interval = ipfixInterval(raw, null);

            assertThat(Double.isNaN(interval))
                    .describedAs("selectorAlgorithm %d produced NaN", algorithm)
                    .isFalse();
            assertThat(interval).isEqualTo(1.0);
        }
    }

    /**
     * The record's selector parameters are ignored even when it carries a full set of them.
     *
     * <p>The specific shape that used to resolve: an exporter advertising a real rate out of band,
     * whose records also carry selector parameters. The old ladder computed 1000 from the record and
     * returned it above the advertised 100. Both numbers are now irrelevant — the parameters are not
     * read from records at all — and the advertised rate is what the flow reports.</p>
     */
    @Test
    void ipfixSelectorParametersOnARecordDoNotOutrankTheAdvertisedRate() {
        final var raw = new IpfixRawFlow();
        raw.selectorAlgorithm = 4;

        final var builder = new IpFixFlowBuilder(IPFIX);
        final Flow flow = builder.buildFlow(Instant.EPOCH, raw, new AdvertisedRate(100.0, null));

        assertResolved(flow, 100.0, SamplingProvenance.Options);
    }

    @Test
    void ipfixPrefersTheRateOnTheRecord() {
        final var raw = new IpfixRawFlow();
        raw.samplingInterval = 512.0;

        final var flow = ipfixFlow(raw, 2000L);

        assertResolved(flow, 512.0, SamplingProvenance.Record);
        // the interval and its provenance are one memoized resolution; reading either a second
        // time must not walk the ladder again and reach a different rung
        assertResolved(flow, 512.0, SamplingProvenance.Record);
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
        final var builder = new IpFixFlowBuilder(IPFIX);
        builder.setFlowSamplingIntervalFallback(2000L);

        // what the table holds after reading a Selector Report: a rate riptide worked out, and the
        // algorithm it worked it out from. An interval alone would have been stated, not computed.
        final Flow flow = builder.buildFlow(Instant.EPOCH, new IpfixRawFlow(),
                new AdvertisedRate(1000.0, null, 3));

        assertResolved(flow, 1000.0, SamplingProvenance.Derived);
    }

    /** And a rate the same table holds as stated is `options`, at the same rung. */
    @Test
    void ipfixNamesAStatedRateAsOptions() {
        final var builder = new IpFixFlowBuilder(IPFIX);
        builder.setFlowSamplingIntervalFallback(2000L);

        final Flow flow = builder.buildFlow(Instant.EPOCH, new IpfixRawFlow(),
                new AdvertisedRate(1000.0, null));

        assertResolved(flow, 1000.0, SamplingProvenance.Options);
    }

    /*
     * `ipfixDoesNotDeriveWhenTheRecordCarriesItsOwnRate` stood here. It set selectorAlgorithm 5 with
     * a degenerate hash range, on the reasoning that an eager ladder would divide by it and cost the
     * whole packet's batch. It could not fail: the record's own rate returns before the selector
     * rung is reached, and the degenerate-range guard yielded null rather than throwing, so the
     * assertion held whether evaluation was lazy or not. With per-record derivation gone there is no
     * longer even a rung to be lazy about — only the table lookup, which the fields it used cannot
     * reach. Its surviving assertions are covered by `ipfixPrefersTheRateOnTheRecord` below.
     */

    // ---- sFlow ------------------------------------------------------------------------------

    /** The frame length the harness reports, so a scaled byte count has something to scale. */
    private static final long FRAME_LENGTH = 1500L;

    /**
     * A v5 datagram carrying one compact flow sample at the given rate, with one
     * {@code sampled_ipv4} record so the sample has a frame length and the counters are real.
     *
     * <p>The rate goes on the wire as a uint32, so a value above {@code 0xFFFFFFFF} cannot be
     * expressed here — {@code writeInt} would truncate it, silently, which is how a test that meant
     * to cover a huge rate ended up sending zero twice.</p>
     */
    private static Datagram sflowDatagram(final long samplingRate) throws Exception {
        return sflowDatagram(samplingRate, FRAME_LENGTH);
    }

    private static Datagram sflowDatagram(final long samplingRate, final long frameLength) throws Exception {
        if (samplingRate < 0 || samplingRate > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("not expressible as a uint32: " + samplingRate);
        }
        if (frameLength < 0 || frameLength > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("not expressible as a uint32: " + frameLength);
        }
        final var record = Unpooled.buffer()
                .writeInt((int) frameLength)
                .writeInt(6)                        // protocol: TCP
                .writeBytes(InetAddress.getByName("192.0.2.10").getAddress())
                .writeBytes(InetAddress.getByName("192.0.2.20").getAddress())
                .writeInt(1234)                     // source port
                .writeInt(80)                       // destination port
                .writeInt(0)                        // TCP flags
                .writeInt(0);                       // ToS
        final var sample = Unpooled.buffer()
                .writeInt(1)                        // sequence
                .writeInt(0)                        // source_id
                .writeInt((int) samplingRate)
                .writeInt(0)                        // sample pool
                .writeInt(0)                        // drops
                .writeInt(0)                        // input interface
                .writeInt(0)                        // output interface
                .writeInt(1)                        // flow record count
                .writeInt(3)                        // record type: sampled_ipv4
                .writeInt(record.readableBytes())
                .writeBytes(record);
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

    /**
     * A rate of {@code 0} off the wire must not reach the flow (#470).
     *
     * <p>{@code samplingInterval = 0} is what marks a rollup row as aggregated before the rate was
     * appended — the type default, and the only marker a sort-key column can have. A live flow
     * carrying {@code 0} would make {@code WHERE samplingInterval > 0} silently drop real traffic
     * and stop {@code = 0} meaning what the schema says.</p>
     *
     * <p>sFlow is the source that could: it reads {@code samplingRate} straight off the wire as a
     * uint32, and nothing in the protocol forbids zero. Driven through the parser rather than by
     * calling the guard directly, because an earlier test did the latter and stayed green when the
     * guard stopped being applied.</p>
     */
    @Test
    void sflowRefusesAZeroRateFromTheWire() throws Exception {
        final var flow = sflowDatagram(0).buildFlows(Instant.EPOCH).findFirst().orElseThrow();

        assertResolved(flow, 1.0, SamplingProvenance.Assumed);
    }

    /**
     * The counters are scaled by the guarded rate too, not by the raw one.
     *
     * <p>Scaling by the wire value while reporting the guarded one gives a row that reads as real
     * unsampled traffic of zero volume: {@code samplingInterval = 1}, {@code bytes = 0},
     * {@code packets = 0}, and nothing marking it as junk. That is worse than dropping the sample,
     * because it is indistinguishable from a genuine idle minute.</p>
     *
     * <p>This exists because the fix for it was made without a test and a mutation survived: the
     * two tests above assert what the flow <em>reports</em>, and both stayed green with the counters
     * still multiplying by {@code sample.samplingRate}.</p>
     */
    @Test
    void sflowScalesTheCountersByTheGuardedRateNotTheWireRate() throws Exception {
        final var junk = sflowDatagram(0).buildFlows(Instant.EPOCH).findFirst().orElseThrow();

        assertThat(junk.getPackets())
                .as("a refused rate scales as 1, so the sample still counts as the one packet it is")
                .isEqualTo(1L);
        assertThat(junk.getBytes())
                .as("and its bytes are the frame length, not the frame length times zero")
                .isEqualTo(FRAME_LENGTH);

        // The counters must still follow a usable rate, or "scale by the guarded value" could be
        // satisfied by never scaling at all.
        final var sampled = sflowDatagram(1024).buildFlows(Instant.EPOCH).findFirst().orElseThrow();

        assertThat(sampled.getPackets()).isEqualTo(1024L);
        assertThat(sampled.getBytes()).isEqualTo(FRAME_LENGTH * 1024L);
    }

    /**
     * The largest rate the wire can carry is usable and must survive unscathed.
     *
     * <p>The guard is one-sided — {@code >= 1.0} — so this is the boundary on the side that must
     * <em>pass</em>. It is here because the test that claimed to cover the rejected side iterated
     * over {@code {0, 0xFFFFFFFF + 1}} while the harness writes the rate with
     * {@code writeInt((int) rate)}: {@code (int) 4294967296L} is {@code 0}, so both iterations sent
     * identical bytes and no large rate was ever put through the parser.</p>
     */
    @Test
    void sflowAcceptsTheLargestRateTheWireCanCarry() throws Exception {
        final long uint32Max = 0xFFFFFFFFL;

        final var flow = sflowDatagram(uint32Max).buildFlows(Instant.EPOCH).findFirst().orElseThrow();

        assertResolved(flow, (double) uint32Max, SamplingProvenance.Record);
    }

    /**
     * The two largest values the wire can carry, together, must not wrap.
     *
     * <p>{@code 4294967295 * 4294967295} is 1.8e19 and overflows a signed long to
     * {@code -8589934591}. The {@code bytes} column is {@code UInt64}, so that negative reads back as
     * the same 1.8e19 — indistinguishable from a measurement, and on its own larger than everything
     * else in any aggregate containing it. sFlow has no transport authentication, so one datagram from
     * anywhere the receiver is bound is enough (#588).</p>
     *
     * <p>Asserted as an exact value rather than a range. A range from zero to the bound is satisfied by
     * almost any wrong-but-positive result — a mistaken clamp to 1 would pass it — which would leave
     * this pinning the absence of a wrap rather than the behaviour.</p>
     */
    @Test
    void sflowBytesCannotWrapAtTheUint32Boundary() throws Exception {
        final long uint32Max = 0xFFFFFFFFL;

        final var flow = sflowDatagram(uint32Max, uint32Max).buildFlows(Instant.EPOCH)
                .findFirst().orElseThrow();

        assertThat(flow.getBytes())
                .as("a frame length past the bound is not a measurement, so it contributes nothing")
                .isZero();
    }

    /**
     * And the bound refuses rather than clamping.
     *
     * <p>Clamping to the bound and scaling anyway yields 5.6e14, which is five orders below the wrap
     * and just as able to swamp an aggregate. This pins the difference: the refused sample contributes
     * nothing, and a sample just inside the bound contributes its real product.</p>
     */
    @Test
    void sflowRefusesAnImpossibleFrameLengthRatherThanClampingIt() throws Exception {
        final long justInside = 131_072L;
        final long justOutside = justInside + 1;

        final var inside = sflowDatagram(1024L, justInside).buildFlows(Instant.EPOCH)
                .findFirst().orElseThrow();
        final var outside = sflowDatagram(1024L, justOutside).buildFlows(Instant.EPOCH)
                .findFirst().orElseThrow();

        assertThat(inside.getBytes()).isEqualTo(justInside * 1024L);
        assertThat(outside.getBytes())
                .as("refused, not clamped to %d * 1024", justInside)
                .isZero();
    }

    /**
     * The bound does not refuse a frame any real medium can carry.
     *
     * <p>Including the largest: {@code frame_length} is the MAC packet length, so a maximum-size IP
     * datagram over Ethernet reports about 65549 rather than 65535. A bound set at 65535 would have
     * discarded it.</p>
     */
    @Test
    void sflowBytesAreUnchangedForARealFrame() throws Exception {
        for (final long frame : new long[]{64L, 1500L, 9216L, 65_535L, 65_549L}) {
            final var flow = sflowDatagram(1024L, frame).buildFlows(Instant.EPOCH)
                    .findFirst().orElseThrow();

            assertThat(flow.getBytes())
                    .describedAs("frame length %d", frame)
                    .isEqualTo(frame * 1024L);
        }
    }

    /**
     * The rate itself is still reported faithfully at the boundary.
     *
     * <p>Bounding the rate would have been the other way to stop the wrap, and it is the wrong one:
     * what an exporter reports is what riptide records (#467), and the wire can carry this.</p>
     */
    @Test
    void sflowStillReportsTheLargestRateAlongsideTheLargestFrame() throws Exception {
        final long uint32Max = 0xFFFFFFFFL;

        final var flow = sflowDatagram(uint32Max, uint32Max).buildFlows(Instant.EPOCH)
                .findFirst().orElseThrow();

        assertResolved(flow, (double) uint32Max, SamplingProvenance.Record);
    }
}
