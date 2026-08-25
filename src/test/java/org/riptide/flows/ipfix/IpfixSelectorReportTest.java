/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.ipfix;

import com.codahale.metrics.MetricRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.riptide.flows.parser.data.Flow;
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
import org.riptide.flows.parser.ipfix.proto.Header;
import org.riptide.flows.parser.ipfix.proto.Packet;
import org.riptide.flows.parser.session.ExporterSamplingTable;
import org.riptide.flows.parser.session.SequenceNumberTracker;
import org.riptide.flows.parser.session.Session;
import org.riptide.flows.parser.session.TcpSession;
import org.riptide.pipeline.ExporterIdentity;

import java.net.InetAddress;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.riptide.flows.utils.BufferUtils.slice;

/**
 * An RFC 5476 §6.5.2 Selector Report driven through the real parse path into the sampling table,
 * and a flow record that names the Selector resolving against it.
 *
 * <p><b>The bytes here are constructed, not captured.</b> Every other blackbox test in this package
 * replays a capture from real hardware; no exporter sending a Selector Report has been available,
 * and none of the twenty-two on the reference fleet sends one. What the construction does buy is
 * the real path: the message goes through {@code Packet}, the template and options-template
 * decoders, the IANA registry lookup and the option tap, so a registry-XML drift or a scope-parsing
 * change breaks this test rather than production alone. What it cannot buy is confirmation that any
 * exporter encodes it this way.</p>
 *
 * <p>The report modelled below is Figure H of RFC 5476, systematic count-based sampling:</p>
 * <pre>
 * options template 400
 *   scope  IE 302  selectorId              = 7
 *   field  IE 304  selectorAlgorithm       = 1   (systematic count-based)
 *   field  IE 305  samplingPacketInterval  = 1
 *   field  IE 306  samplingPacketSpace     = 99
 * </pre>
 */
public class IpfixSelectorReportTest {

    private static final List<ValueVisitor<?>> VISITORS = List.of(
            new BooleanVisitor(), new DoubleVisitor(), new DurationVisitor(), new InetAddressVisitor(),
            new InstantVisitor(), new IntegerVisitor(), new LongVisitor(), new StringVisitor(),
            new UnsignedLongVisitor());

    private static final int OBSERVATION_DOMAIN = 7;

    /** {@code (1 + 99) / 1}: one packet selected out of every hundred. */
    private static final double EXPECTED_RATE = 100.0;

    private final ExporterSamplingTable table = new ExporterSamplingTable(new MetricRegistry());

    private final Session session =
            new TcpSession(InetAddress.getLoopbackAddress(), () -> new SequenceNumberTracker(32), this.table);

    private final ExporterIdentity identity =
            new ExporterIdentity.NetflowIpfix(InetAddress.getLoopbackAddress(), OBSERVATION_DOMAIN);

    private IpFixFlowBuilder builder() {
        final var builder = new IpFixFlowBuilder(new ValueConversionService(IpfixRawFlow.class, VISITORS));
        builder.setSamplingTable(this.table);
        return builder;
    }

    private static ByteBuf message() {
        final ByteBuf b = Unpooled.buffer();
        b.writeShort(0x000A).writeShort(0).writeInt(1_700_000_000).writeInt(1).writeInt(OBSERVATION_DOMAIN);
        return b;
    }

    private static void patchAndSend(final Session session, final ByteBuf b) throws Exception {
        b.setShort(2, b.readableBytes());
        final Header header = new Header(slice(b, Header.SIZE));
        new Packet(session, header, slice(b, header.payloadLength()));
    }

    /** The Selector Report of Figure H, for the given selector and packet interval/space. */
    private void feedSelectorReport(final long selectorId, final int interval, final int space) throws Exception {
        feedSelectorReport(selectorId, 1, interval, space);
    }

    private void feedSelectorReport(final long selectorId, final int algorithm,
                                    final int interval, final int space) throws Exception {
        final ByteBuf b = message();
        // options template set (id 3): template 400, fieldCount 4, scopeFieldCount 1
        b.writeShort(3).writeShort(4 + 6 + 4 * 4);
        b.writeShort(400).writeShort(4).writeShort(1);
        b.writeShort(302).writeShort(8);  // scope: selectorId
        b.writeShort(304).writeShort(2);  // selectorAlgorithm
        b.writeShort(305).writeShort(4);  // samplingPacketInterval
        b.writeShort(306).writeShort(4);  // samplingPacketSpace
        // data set for template 400
        b.writeShort(400).writeShort(4 + 8 + 2 + 4 + 4);
        b.writeLong(selectorId);
        b.writeShort(algorithm);
        b.writeInt(interval);
        b.writeInt(space);
        patchAndSend(this.session, b);
    }

    /** A flow record naming a Selector, or none when {@code selectorId} is null. */
    private Packet flowRecord(final Long selectorId) throws Exception {
        final ByteBuf b = message();
        final int fields = selectorId != null ? 3 : 2;
        b.writeShort(2).writeShort(4 + 4 + fields * 4);
        b.writeShort(401).writeShort(fields);
        b.writeShort(1).writeShort(8);    // octetDeltaCount
        b.writeShort(2).writeShort(8);    // packetDeltaCount
        if (selectorId != null) {
            b.writeShort(302).writeShort(8);
        }
        b.writeShort(401).writeShort(4 + fields * 8);
        b.writeLong(1_500L);
        b.writeLong(10L);
        if (selectorId != null) {
            b.writeLong(selectorId);
        }
        b.setShort(2, b.readableBytes());
        final Header header = new Header(slice(b, Header.SIZE));
        return new Packet(this.session, header, slice(b, header.payloadLength()));
    }

    private List<Flow> flowsNaming(final Long selectorId) throws Exception {
        return builder().buildFlows(Instant.EPOCH, flowRecord(selectorId), this.identity).toList();
    }

    /**
     * A harness check, not a binding check: the constructed bytes decode into one flow with the
     * counters written into them.
     *
     * <p>It was first written as "a flow record carries the Selector it names", which it does not
     * pin: renaming {@code IpfixRawFlow.selectorId} so IE 302 binds to nothing leaves this test
     * green. Verified by that mutation rather than by reading. The binding is pinned by the four
     * tests below that resolve a rate through it — each of them goes red under the same
     * mutation — and this one exists so that a malformed template fails loudly here instead of
     * confusingly there.</p>
     */
    @Test
    public void theConstructedBytesDecodeIntoOneFlow() throws Exception {
        final List<Flow> flows = flowsNaming(7L);

        assertThat(flows).hasSize(1);
        assertThat(flows.getFirst().getBytes()).isEqualTo(1_500L);
        assertThat(flows.getFirst().getPackets()).isEqualTo(10L);
    }

    /** The rung this change adds: parameters in an options record, resolved by the record's id. */
    @Test
    public void aSelectorReportSuppliesTheRateForFlowsNamingIt() throws Exception {
        feedSelectorReport(7L, 1, 99);

        assertThat(flowsNaming(7L)).allSatisfy(flow -> {
            assertThat(flow.getSamplingInterval()).isEqualTo(EXPECTED_RATE);
            assertThat(flow.getSamplingProvenance())
                    .as("a rate riptide computed from the report's parameters is `derived`")
                    .isEqualTo(Flow.SamplingProvenance.Derived);
        });
    }

    /** And the algorithm travels with it, from the report rather than the record. */
    @Test
    public void theReportsAlgorithmNamesTheSamplingMode() throws Exception {
        feedSelectorReport(7L, 1, 99);

        assertThat(flowsNaming(7L)).allSatisfy(flow -> assertThat(flow.getSamplingAlgorithm())
                .isEqualTo(Flow.SamplingAlgorithm.SystematicCountBasedSampling));
    }

    /**
     * One exporter may run several Selectors, which is why RFC 5476 scopes each report by
     * {@code selectorId} and why the table keys by it rather than by exporter alone.
     */
    @Test
    public void twoSelectorsOnOneExporterDoNotShareARate() throws Exception {
        feedSelectorReport(7L, 1, 99);     // 1 in 100
        feedSelectorReport(8L, 1, 999);    // 1 in 1000

        assertThat(flowsNaming(7L)).allSatisfy(f -> assertThat(f.getSamplingInterval()).isEqualTo(100.0));
        assertThat(flowsNaming(8L)).allSatisfy(f -> assertThat(f.getSamplingInterval()).isEqualTo(1000.0));
    }

    /**
     * A flow naming no Selector gets the exporter-wide rate and nothing else.
     *
     * <p>Matching an unreferenced report to it would mean guessing which Selector produced the
     * flow, and an exporter running several gives no basis for the guess. Falling through is the
     * same path every other unresolvable flow takes.</p>
     */
    @Test
    public void aFlowNamingNoSelectorDoesNotBorrowAReportsRate() throws Exception {
        feedSelectorReport(7L, 1, 99);

        assertThat(flowsNaming(null)).allSatisfy(flow -> {
            assertThat(flow.getSamplingInterval()).isEqualTo(1.0);
            assertThat(flow.getSamplingProvenance()).isEqualTo(Flow.SamplingProvenance.Assumed);
        });
    }

    /**
     * A report naming an algorithm that expresses no ratio teaches nothing, and must not leave a
     * fabricated 1.0 behind that then outranks the configured fallback.
     */
    @Test
    public void aFilteringSelectorTeachesNoRate() throws Exception {
        feedSelectorReport(7L, 5, 1, 99);   // property match filtering

        final var builder = builder();
        builder.setFlowSamplingIntervalFallback(2000L);
        final List<Flow> flows = builder.buildFlows(Instant.EPOCH, flowRecord(7L), this.identity).toList();

        assertThat(flows).allSatisfy(flow -> {
            assertThat(flow.getSamplingInterval()).isEqualTo(2000.0);
            assertThat(flow.getSamplingProvenance()).isEqualTo(Flow.SamplingProvenance.Fallback);
        });
    }

    /**
     * An exporter sending both record shapes resolves each on its own terms, whichever arrives
     * first. One map keyed by exporter alone would make the answer depend on arrival order.
     */
    @Test
    public void aStatedRateAndASelectorReportDoNotOverwriteEachOther() throws Exception {
        feedSelectorReport(7L, 1, 99);     // computed: 100 for selector 7
        feedSamplerOptions(512);           // stated: 512 for the exporter

        assertThat(flowsNaming(7L)).allSatisfy(flow -> {
            assertThat(flow.getSamplingInterval()).isEqualTo(100.0);
            assertThat(flow.getSamplingProvenance()).isEqualTo(Flow.SamplingProvenance.Derived);
        });
        assertThat(flowsNaming(null)).allSatisfy(flow -> {
            assertThat(flow.getSamplingInterval()).isEqualTo(512.0);
            assertThat(flow.getSamplingProvenance()).isEqualTo(Flow.SamplingProvenance.Options);
        });
    }

    /** The same pair in the other order, because arrival order is not something riptide controls. */
    @Test
    public void theOrderTheTwoRecordShapesArriveInDoesNotMatter() throws Exception {
        feedSamplerOptions(512);
        feedSelectorReport(7L, 1, 99);

        assertThat(flowsNaming(7L)).allSatisfy(f -> assertThat(f.getSamplingInterval()).isEqualTo(100.0));
        assertThat(flowsNaming(null)).allSatisfy(f -> assertThat(f.getSamplingInterval()).isEqualTo(512.0));
    }

    /**
     * A Selector-scoped record that states its rate outright belongs to that Selector, not to the
     * exporter.
     *
     * <p>Routing on which fields are present rather than on the scope files this exporter-wide, and
     * two Selectors announcing rates that way overwrite one another with the last to arrive.</p>
     *
     * <p>The provenance is {@code record} rather than {@code options}, which surprised this test
     * into being written wrong first. riptide's per-record option merge already applies a
     * scoped options record to data records matching that scope, so an IE 34 scoped by
     * {@code selectorId} 7 is merged onto every flow naming selector 7 and reaches the ladder's top
     * rung before the table is consulted at all. That is ordinary IPFIX scoping and predates this
     * change. It means the merge, not the table, is what carries this particular shape — but the
     * table must still key it per Selector, because the merge only covers flows that carry
     * {@code selectorId} and the table is what answers for everything else.</p>
     */
    @Test
    public void twoSelectorsStatingRatesOutrightDoNotOverwriteEachOther() throws Exception {
        feedSelectorScopedInterval(7L, 100);
        feedSelectorScopedInterval(8L, 1000);

        assertThat(flowsNaming(7L)).allSatisfy(flow -> {
            assertThat(flow.getSamplingInterval()).isEqualTo(100.0);
            assertThat(flow.getSamplingProvenance()).isEqualTo(Flow.SamplingProvenance.Record);
        });
        assertThat(flowsNaming(8L)).allSatisfy(f -> assertThat(f.getSamplingInterval()).isEqualTo(1000.0));
    }

    /**
     * And the table keyed it per Selector too, which is what answers when the merge cannot.
     *
     * <p>Asserted through the table directly because the merge would mask it through the builder:
     * this is the state a flow would resolve against if it named the Selector without the exporter
     * having scoped an interval onto it.</p>
     */
    @Test
    public void theTableKeepsSelectorScopedRatesApartFromEachOther() throws Exception {
        feedSelectorScopedInterval(7L, 100);
        feedSelectorScopedInterval(8L, 1000);

        assertThat(this.table.lookup(this.identity, 7L))
                .hasValueSatisfying(rate -> {
                    assertThat(rate.interval()).isEqualTo(100.0);
                    assertThat(rate.computed())
                            .as("stated outright, so nothing was derived and provenance must say so")
                            .isFalse();
                });
        assertThat(this.table.lookup(this.identity, 8L))
                .hasValueSatisfying(rate -> assertThat(rate.interval()).isEqualTo(1000.0));
        assertThat(this.table.lookup(this.identity).map(ExporterSamplingTable.AdvertisedRate::interval))
                .as("a stated rate is also mirrored exporter-wide, for flows that name no Selector; "
                        + "last write wins there, which is what this record shape did before #594")
                .contains(1000.0);
    }

    /**
     * A Selector that stops expressing a rate withdraws the one it taught.
     *
     * <p>The stated path invalidates on an unusable re-advertisement, on the reasoning that an
     * exporter re-announcing is describing its current configuration. The same applies here: a
     * Selector reconfigured from sampling to filtering re-sends a report riptide can read nothing
     * from, and serving the old rate until the retention window expires would multiply every flow
     * from it by a rate that no longer exists.</p>
     */
    @Test
    public void aSelectorReconfiguredToFilteringWithdrawsItsRate() throws Exception {
        feedSelectorReport(7L, 1, 99);
        assertThat(flowsNaming(7L)).allSatisfy(f -> assertThat(f.getSamplingInterval()).isEqualTo(100.0));

        feedSelectorReport(7L, 5, 1, 99);   // now property match filtering: expresses no ratio

        assertThat(flowsNaming(7L)).allSatisfy(flow -> {
            assertThat(flow.getSamplingInterval())
                    .as("the withdrawn rate must not survive its own withdrawal")
                    .isEqualTo(1.0);
            assertThat(flow.getSamplingProvenance()).isEqualTo(Flow.SamplingProvenance.Assumed);
        });
    }

    /**
     * A selector id past 2^53 keys the same on both sides.
     *
     * <p>IE 302 is an unsigned64. Read as a double on the write side and as an exact
     * {@code UnsignedLong} on the read side, the two keys diverge above 2^53 and can never match
     * above 2^63 — the report becomes unreachable with nothing to show for it but a rising
     * unresolved-lookup meter.</p>
     */
    @Test
    public void aWideSelectorIdKeysTheSameOnBothSides() throws Exception {
        final long wide = (1L << 60) + 12_345L;

        feedSelectorReport(wide, 1, 99);

        assertThat(flowsNaming(wide)).allSatisfy(flow -> {
            assertThat(flow.getSamplingInterval()).isEqualTo(EXPECTED_RATE);
            assertThat(flow.getSamplingProvenance()).isEqualTo(Flow.SamplingProvenance.Derived);
        });
    }

    /** A Selector-scoped record whose rate is stated as IE 34 rather than as parameters. */
    private void feedSelectorScopedInterval(final long selectorId, final int interval) throws Exception {
        final ByteBuf b = message();
        b.writeShort(3).writeShort(4 + 6 + 2 * 4);
        b.writeShort(403).writeShort(2).writeShort(1);
        b.writeShort(302).writeShort(8);  // scope: selectorId
        b.writeShort(34).writeShort(4);   // samplingInterval
        b.writeShort(403).writeShort(4 + 8 + 4);
        b.writeLong(selectorId);
        b.writeInt(interval);
        patchAndSend(this.session, b);
    }

    /** A plain IE 34 sampler options record, the shape the SRX sends. */
    private void feedSamplerOptions(final int interval) throws Exception {
        final ByteBuf b = message();
        b.writeShort(3).writeShort(4 + 6 + 2 * 4);
        b.writeShort(402).writeShort(2).writeShort(1);
        b.writeShort(149).writeShort(4);  // scope: observationDomainId
        b.writeShort(34).writeShort(4);   // samplingInterval
        b.writeShort(402).writeShort(4 + 4 + 4);
        b.writeInt(OBSERVATION_DOMAIN);
        b.writeInt(interval);
        patchAndSend(this.session, b);
    }

    /** An exporter whose only advertisement is Selector-scoped, and whose flows name no Selector. */
    @Test
    public void aSelectorScopedIntervalStillReachesFlowsThatNameNoSelector() throws Exception {
        feedSelectorScopedInterval(7L, 1000);

        assertThat(flowsNaming(null)).allSatisfy(flow -> {
            assertThat(flow.getSamplingInterval()).isEqualTo(1000.0);
            assertThat(flow.getSamplingProvenance()).isEqualTo(Flow.SamplingProvenance.Options);
        });
    }

    /** A Selector-scoped record that is neither a report nor a rate must not withdraw the rate. */
    @Test
    public void anUnrelatedSelectorScopedRecordDoesNotWithdrawTheRate() throws Exception {
        feedSelectorReport(7L, 1, 99);

        // scoped by selectorId, carrying neither selectorAlgorithm nor an interval
        final ByteBuf b = message();
        b.writeShort(3).writeShort(4 + 6 + 2 * 4);
        b.writeShort(404).writeShort(2).writeShort(1);
        b.writeShort(302).writeShort(8);   // scope: selectorId
        b.writeShort(318).writeShort(8);   // selectorIdTotalPktsObserved
        b.writeShort(404).writeShort(4 + 8 + 8);
        b.writeLong(7L);
        b.writeLong(123_456L);
        patchAndSend(this.session, b);

        assertThat(flowsNaming(7L)).allSatisfy(flow -> assertThat(flow.getSamplingInterval())
                .as("an unrecognised record is not a withdrawal")
                .isEqualTo(EXPECTED_RATE));
    }

    /**
     * A filtering advertisement must not retract a sampling rate another record taught.
     *
     * <p>The exporter-wide entry has several writers, so withdrawal there is not available the way it
     * is for a Selector's own key. A device may sample and filter at once — "I filter on a property"
     * is an additional process, not a retraction of "I sample 1:1000".</p>
     */
    @Test
    public void aFilteringAdvertisementDoesNotRetractAStatedRate() throws Exception {
        feedSamplerOptions(1000);
        feedUnscopedAlgorithm(5);          // property match filtering: expresses no ratio

        assertThat(flowsNaming(null)).allSatisfy(flow -> {
            assertThat(flow.getSamplingInterval())
                    .as("the stated rate must survive an advertisement that teaches nothing")
                    .isEqualTo(1000.0);
            assertThat(flow.getSamplingProvenance()).isEqualTo(Flow.SamplingProvenance.Options);
        });
    }

    /** An algorithm with none of its parameters teaches nothing and displaces nothing. */
    @Test
    public void anIncompletelyStatedAdvertisementTeachesNothing() throws Exception {
        feedSamplerOptions(1000);
        feedUnscopedAlgorithm(1);          // systematic count-based, but no 305/306

        assertThat(flowsNaming(null))
                .allSatisfy(f -> assertThat(f.getSamplingInterval()).isEqualTo(1000.0));
    }

    /** A stated interval wins over parameters when one record carries both. */
    @Test
    public void aStatedIntervalOutranksParametersOnTheSameRecord() throws Exception {
        final ByteBuf b = message();
        // BOTH must be complete, or precedence is never reached. An earlier version of this test
        // omitted IE 306, so the advertisement branch bailed on incomplete parameters and the test
        // passed no matter which handler ran first — found by mutation, not by reading.
        b.writeShort(3).writeShort(4 + 6 + 5 * 4);
        b.writeShort(405).writeShort(5).writeShort(1);
        b.writeShort(143).writeShort(4);   // scope: meteringProcessId
        b.writeShort(34).writeShort(4);    // samplingInterval, stated: 512
        b.writeShort(304).writeShort(2);   // selectorAlgorithm 1
        b.writeShort(305).writeShort(4);   // samplingPacketInterval 1
        b.writeShort(306).writeShort(4);   // samplingPacketSpace  99  -> would compute 100
        b.writeShort(405).writeShort(4 + 4 + 4 + 2 + 4 + 4);
        b.writeInt(1);
        b.writeInt(512);
        b.writeShort(1);
        b.writeInt(1);
        b.writeInt(99);
        patchAndSend(this.session, b);

        assertThat(flowsNaming(null)).allSatisfy(flow -> {
            assertThat(flow.getSamplingInterval()).isEqualTo(512.0);
            assertThat(flow.getSamplingProvenance()).isEqualTo(Flow.SamplingProvenance.Options);
        });
    }

    /** An options record scoped by something other than selectorId, stating an algorithm. */
    private void feedUnscopedAlgorithm(final int algorithm) throws Exception {
        final ByteBuf b = message();
        b.writeShort(3).writeShort(4 + 6 + 2 * 4);
        b.writeShort(406).writeShort(2).writeShort(1);
        b.writeShort(143).writeShort(4);   // scope: meteringProcessId
        b.writeShort(304).writeShort(2);   // selectorAlgorithm only, no parameters
        b.writeShort(406).writeShort(4 + 4 + 2);
        b.writeInt(1);
        b.writeShort(algorithm);
        patchAndSend(this.session, b);
    }

    /**
     * An interval field that states no rate must not veto one stated elsewhere on the same record.
     *
     * <p>`0` means the field carries no rate, and `1` means "not sampling" — which contradicts a
     * record simultaneously stating an algorithm and parameters computing to 100. In that
     * contradiction the algorithm wins: a deprecated single field is the one an exporter is likely to
     * have defaulted, and preferring it would continue the very bias #598 exists to remove.</p>
     */
    @Test
    public void anIntervalStatingNoRateDoesNotVetoTheParametersBesideIt() throws Exception {
        feedStatedAndParameters(0);

        assertThat(flowsNaming(null)).allSatisfy(flow -> {
            assertThat(flow.getSamplingInterval())
                    .as("IE 34 = 0 states nothing; the parameters state 1:100")
                    .isEqualTo(100.0);
            assertThat(flow.getSamplingProvenance()).isEqualTo(Flow.SamplingProvenance.Derived);
        });
    }

    @Test
    public void anExplicitOneDoesNotVetoParametersStatingARealRatio() throws Exception {
        feedStatedAndParameters(1);

        assertThat(flowsNaming(null))
                .allSatisfy(f -> assertThat(f.getSamplingInterval()).isEqualTo(100.0));
    }

    /**
     * A filtering algorithm must not overwrite the exporter-wide sampling rate.
     *
     * <p>Filtering and sampling compose multiplicatively, and this key cannot hold both. Measured
     * before the guard: a 1:2 hash filter arriving after a stated 1:1000 replaced it, leaving every
     * flow scaled 500x too low and labelled `derived`. Per Selector this is expressible because each
     * Selector owns its entry; exporter-wide it is not. See #596.</p>
     */
    @Test
    public void aHashFilterDoesNotOverwriteTheExporterWideSamplingRate() throws Exception {
        feedSamplerOptions(1000);
        feedHashFilter();          // selects 1 of 2 hash buckets: a real ratio, wrong key

        assertThat(flowsNaming(null)).allSatisfy(flow -> {
            assertThat(flow.getSamplingInterval())
                    .as("the sampler's rate must survive a filter advertisement")
                    .isEqualTo(1000.0);
            assertThat(flow.getSamplingProvenance()).isEqualTo(Flow.SamplingProvenance.Options);
        });
    }

    /** IE 34 alongside a complete selector-algorithm statement, under a non-Selector scope. */
    private void feedStatedAndParameters(final int stated) throws Exception {
        final ByteBuf b = message();
        b.writeShort(3).writeShort(4 + 6 + 5 * 4);
        b.writeShort(410 + stated).writeShort(5).writeShort(1);
        b.writeShort(143).writeShort(4);
        b.writeShort(34).writeShort(4);
        b.writeShort(304).writeShort(2);
        b.writeShort(305).writeShort(4);
        b.writeShort(306).writeShort(4);
        b.writeShort(410 + stated).writeShort(4 + 4 + 4 + 2 + 4 + 4);
        b.writeInt(1);
        b.writeInt(stated);
        b.writeShort(1);
        b.writeInt(1);
        b.writeInt(99);
        patchAndSend(this.session, b);
    }

    /** Hash-based filtering selecting one bucket of two, under a non-Selector scope. */
    private void feedHashFilter() throws Exception {
        final ByteBuf b = message();
        b.writeShort(3).writeShort(4 + 6 + 6 * 4);
        b.writeShort(415).writeShort(6).writeShort(1);
        b.writeShort(143).writeShort(4);
        b.writeShort(304).writeShort(2);
        b.writeShort(329).writeShort(8);
        b.writeShort(330).writeShort(8);
        b.writeShort(331).writeShort(8);
        b.writeShort(332).writeShort(8);
        b.writeShort(415).writeShort(4 + 4 + 2 + 32);
        b.writeInt(1);
        b.writeShort(8);
        b.writeLong(0); b.writeLong(1);   // output range 0..1
        b.writeLong(0); b.writeLong(0);   // selected 0..0  -> ratio 2
        patchAndSend(this.session, b);
    }
}
