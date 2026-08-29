/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser.session;

import com.codahale.metrics.MetricRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.riptide.flows.parser.ie.Value;
import org.riptide.flows.parser.ie.values.UnsignedValue;
import org.riptide.pipeline.ExporterIdentity;

import java.net.InetAddress;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sampler options table. Field ids, names and order follow the captured ASR9k template
 * {@code netflow9_test_cisco_asr9k_opttpl257.dat}: scope System, fields 48/50/49/84.
 */
class ExporterSamplingTableTest {

    private ExporterSamplingTable table;
    private MetricRegistry metrics;

    private static ExporterIdentity exporter(final String address, final long domain) throws Exception {
        return new ExporterIdentity.NetflowIpfix(InetAddress.getByName(address), domain);
    }

    /** The shape the ASR9k sampler table sends: a random interval alongside id, mode and name. */
    private static List<Value<?>> samplerRecord(final long interval) {
        return List.of(
                new UnsignedValue("FLOW_SAMPLER_ID", 1),
                new UnsignedValue("FLOW_SAMPLER_RANDOM_INTERVAL", interval),
                new UnsignedValue("FLOW_SAMPLER_MODE", 2));
    }

    @BeforeEach
    void setUp() {
        this.metrics = new MetricRegistry();
        this.table = new ExporterSamplingTable(this.metrics);
    }

    @Test
    void learnsTheRateFromASamplerOptionsRecord() throws Exception {
        final var exporter = exporter("192.0.2.1", 0);

        table.accept(exporter, List.of(), samplerRecord(1000));

        assertThat(table.lookup(exporter).map(ExporterSamplingTable.AdvertisedRate::interval)).contains(1000.0);
    }

    /** IPFIX names the same thing differently; one table serves both protocols. */
    @Test
    void learnsTheRateFromIpfixFieldNames() throws Exception {
        final var exporter = exporter("192.0.2.1", 0);

        table.accept(exporter, List.of(), List.of(new UnsignedValue("samplerRandomInterval", 512)));

        assertThat(table.lookup(exporter).map(ExporterSamplingTable.AdvertisedRate::interval)).contains(512.0);
    }

    /**
     * Field 34 and the 305/306 pair mean different things, and conflating them is what put Nokia
     * SROS 500x out in Akvorado. Only the interval is read here; a bare packet-space field is not
     * mistaken for one.
     */
    @Test
    void doesNotTreatUnrelatedSamplingFieldsAsTheInterval() throws Exception {
        final var exporter = exporter("192.0.2.1", 0);

        table.accept(exporter, List.of(), List.of(
                new UnsignedValue("samplingPacketSpace", 499),
                new UnsignedValue("samplingSize", 1)));

        assertThat(table.lookup(exporter)).isEmpty();
    }

    @Test
    void ignoresOptionRecordsThatAreNotAboutSampling() throws Exception {
        final var exporter = exporter("192.0.2.1", 0);

        // the interface table's record, which shares the same option tap
        table.accept(exporter, List.of(), List.of(new UnsignedValue("INPUT_SNMP", 7)));

        assertThat(table.lookup(exporter)).isEmpty();
        assertThat(metrics.meter("parser.optionSampling.consumed").getCount()).isZero();
    }

    /** 0 is a placeholder, not a rate, so nothing is learned from it. */
    @Test
    void skipsAZeroRate() throws Exception {
        final var exporter = exporter("192.0.2.1", 0);

        table.accept(exporter, List.of(), samplerRecord(0));

        assertThat(table.lookup(exporter)).isEmpty();
        assertThat(metrics.meter("parser.optionSampling.skipped").getCount()).isEqualTo(1);
    }

    /**
     * An exporter advertising 1 has stated it does not sample. That is an answer, and keeping it
     * stops a receiver-wide fallback meant for a different exporter from overriding this one.
     */
    @Test
    void keepsAnExplicitlyUnsampledRate() throws Exception {
        final var exporter = exporter("192.0.2.1", 0);

        table.accept(exporter, List.of(), samplerRecord(1));

        assertThat(table.lookup(exporter).map(ExporterSamplingTable.AdvertisedRate::interval)).contains(1.0);
    }

    @Test
    void keepsExportersApart() throws Exception {
        final var first = exporter("192.0.2.1", 0);
        final var second = exporter("192.0.2.2", 0);

        table.accept(first, List.of(), samplerRecord(1000));

        assertThat(table.lookup(first).map(ExporterSamplingTable.AdvertisedRate::interval)).contains(1000.0);
        assertThat(table.lookup(second)).isEmpty();
    }

    /** Two observation domains behind one address are two exporters, and may sample differently. */
    @Test
    void keepsObservationDomainsApart() throws Exception {
        final var domainZero = exporter("192.0.2.1", 0);
        final var domainOne = exporter("192.0.2.1", 1);

        table.accept(domainZero, List.of(), samplerRecord(1000));
        table.accept(domainOne, List.of(), samplerRecord(100));

        assertThat(table.lookup(domainZero).map(ExporterSamplingTable.AdvertisedRate::interval)).contains(1000.0);
        assertThat(table.lookup(domainOne).map(ExporterSamplingTable.AdvertisedRate::interval)).contains(100.0);
    }

    @Test
    void aLaterRecordSupersedesAnEarlierOne() throws Exception {
        final var exporter = exporter("192.0.2.1", 0);

        table.accept(exporter, List.of(), samplerRecord(1000));
        table.accept(exporter, List.of(), samplerRecord(2000));

        assertThat(table.lookup(exporter).map(ExporterSamplingTable.AdvertisedRate::interval)).contains(2000.0);
    }

    @Test
    void metersResolvedAndUnresolvedLookups() throws Exception {
        final var known = exporter("192.0.2.1", 0);
        final var unknown = exporter("192.0.2.9", 0);
        table.accept(known, List.of(), samplerRecord(1000));

        table.lookup(known);
        table.lookup(unknown);
        table.lookup(null);

        assertThat(metrics.meter("parser.optionSampling.resolved").getCount()).isEqualTo(1);
        assertThat(metrics.meter("parser.optionSampling.unresolved").getCount()).isEqualTo(2);
    }

    /** The mode travels with the rate, so a flow resolving one can resolve the other. */
    @Test
    void learnsTheSamplingModeAlongsideTheRate() throws Exception {
        final var exporter = exporter("192.0.2.1", 0);

        table.accept(exporter, List.of(), samplerRecord(1000));

        assertThat(table.lookup(exporter).map(ExporterSamplingTable.AdvertisedRate::mode)).contains(2);
    }

    /** An exporter re-advertising 0 has turned sampling off; the stale rate must not outlive it. */
    @Test
    void aZeroRateWithdrawsWhatWasLearned() throws Exception {
        final var exporter = exporter("192.0.2.1", 0);
        table.accept(exporter, List.of(), samplerRecord(1000));

        table.accept(exporter, List.of(), samplerRecord(0));

        assertThat(table.lookup(exporter)).isEmpty();
    }

    /**
     * Every verdict this table can return, pinned (#599).
     *
     * <p>Not optional detail. In the first attempt at this change three mutations inverting these
     * returns survived the full suite, because the fan-out tests used stubs and nothing asserted
     * what a real table reports. The meter's whole meaning rests on these.</p>
     */
    @Test
    void aSamplerRecordStatingRateOneIsClaimed() throws Exception {
        // A rate of 1 means "not sampling", which is an answer: stored, and therefore taken. The
        // internal boolean this used to reuse said `false` here, which would have reported a record
        // riptide acted on as a gap.
        assertThat(this.table.accept(exporter("192.0.2.1", 0), List.of(), samplerRecord(1)))
                .isEqualTo(OptionListener.Verdict.CLAIMED);
    }

    @Test
    void aSamplerRecordStatingAUsableRateIsClaimed() throws Exception {
        assertThat(this.table.accept(exporter("192.0.2.1", 0), List.of(), samplerRecord(1000)))
                .isEqualTo(OptionListener.Verdict.CLAIMED);
    }

    /** An interval of 0 is a withdrawal: understood, and nothing left to serve. */
    @Test
    void aSamplerRecordWithdrawingItsRateIsRecognisedButUnusable() throws Exception {
        assertThat(this.table.accept(exporter("192.0.2.1", 0), List.of(), samplerRecord(0)))
                .isEqualTo(OptionListener.Verdict.RECOGNISED_BUT_UNUSABLE);
    }

    /** Nothing about sampling at all: not this table's shape. */
    @Test
    void aRecordAboutNeitherRateNorSelectorIsUnrecognised() throws Exception {
        assertThat(this.table.accept(exporter("192.0.2.1", 0), List.of(),
                List.of(new UnsignedValue("INPUT_SNMP", 7))))
                .isEqualTo(OptionListener.Verdict.UNRECOGNISED);
    }

    /**
     * An exporter-wide sampling advertisement that computes to a real rate is claimed.
     *
     * <p>The softflowd shape, and the case this whole change leans on: a record scoped by the
     * metering process rather than a Selector, stating an algorithm and parameters riptide computes
     * a rate from. Since #604 it is claimed by this table and declined by the interface table, which
     * is what makes it the <em>negative</em> witness for the unclaimed meters — they must stay flat
     * for it.</p>
     *
     * <p>Unpinned until now: a mutation inverting this verdict survived the entire suite twice.</p>
     */
    @Test
    void anAdvertisementComputingARealRateIsClaimed() throws Exception {
        assertThat(this.table.accept(exporter("192.0.2.1", 0), List.of(),
                List.of(new UnsignedValue("selectorAlgorithm", 1),
                        new UnsignedValue("samplingPacketInterval", 1),
                        new UnsignedValue("samplingPacketSpace", 99))))
                .isEqualTo(OptionListener.Verdict.CLAIMED);
    }

    /**
     * An advertisement riptide reads and deliberately discards is recognised, not unrecognised.
     *
     * <p>A filtering algorithm expresses a ratio riptide cannot store exporter-wide (#596). That is
     * precisely the loss the meters exist to surface, so it must not be filed among the shapes
     * nobody recognised.</p>
     */
    @Test
    void aFilteringAlgorithmIsRecognisedButUnusable() throws Exception {
        assertThat(this.table.accept(exporter("192.0.2.1", 0), List.of(),
                List.of(new UnsignedValue("selectorAlgorithm", 6),
                        new UnsignedValue("samplingPacketInterval", 1),
                        new UnsignedValue("samplingPacketSpace", 1))))
                .isEqualTo(OptionListener.Verdict.RECOGNISED_BUT_UNUSABLE);
    }

    /**
     * A selectorId scope alone is not a claim.
     *
     * <p>The blind spot the review found: returning CLAIMED for anything scoped by selectorId made
     * the meters structurally unable to report a selectorId-scoped record riptide dropped, which is
     * the #598 shape.</p>
     */
    @Test
    void aSelectorScopedRecordAboutNothingRiptideReadsIsUnrecognised() throws Exception {
        assertThat(this.table.accept(exporter("192.0.2.1", 0),
                List.of(new UnsignedValue("selectorId", 4)),
                List.of(new UnsignedValue("INPUT_SNMP", 7))))
                .isEqualTo(OptionListener.Verdict.UNRECOGNISED);
    }

    /**
     * The Selector Report verdicts, pinned one by one.
     *
     * <p>{@code IpfixSelectorReportTest} drives every one of these branches through real packets
     * but installs the table directly, so it observes flow provenance and never the verdict. Each
     * return below could be flipped to {@code UNRECOGNISED} with that suite green.</p>
     */
    @Test
    void aSelectorReportComputingARealRateIsClaimed() throws Exception {
        assertThat(this.table.accept(exporter("192.0.2.1", 0),
                List.of(new UnsignedValue("selectorId", 4)),
                List.of(new UnsignedValue("selectorAlgorithm", 1),
                        new UnsignedValue("samplingPacketInterval", 1),
                        new UnsignedValue("samplingPacketSpace", 99))))
                .isEqualTo(OptionListener.Verdict.CLAIMED);
    }

    @Test
    void aSelectorScopedRecordStatingAUsableRateIsClaimed() throws Exception {
        assertThat(this.table.accept(exporter("192.0.2.1", 0),
                List.of(new UnsignedValue("selectorId", 4)),
                samplerRecord(1000)))
                .isEqualTo(OptionListener.Verdict.CLAIMED);
    }

    /** A Selector reconfigured to filtering: read, and served nothing from (#596). */
    @Test
    void aSelectorReportNamingANonRatioAlgorithmIsRecognisedButUnusable() throws Exception {
        assertThat(this.table.accept(exporter("192.0.2.1", 0),
                List.of(new UnsignedValue("selectorId", 4)),
                List.of(new UnsignedValue("selectorAlgorithm", 6),
                        new UnsignedValue("hashOutputRangeMin", 0),
                        new UnsignedValue("hashOutputRangeMax", 1))))
                .isEqualTo(OptionListener.Verdict.RECOGNISED_BUT_UNUSABLE);
    }

    /**
     * A Selector-scoped withdrawal drops that Selector's rate, so flows naming it stop resolving a
     * rate the exporter has retracted. Before this, the entry served until retention expired.
     */
    @Test
    void aSelectorScopedWithdrawalDropsThatSelectorsRate() throws Exception {
        final var exporter = exporter("192.0.2.1", 0);
        final var selector = List.<Value<?>>of(new UnsignedValue("selectorId", 4));
        this.table.accept(exporter, selector,
                List.of(new UnsignedValue("selectorAlgorithm", 1),
                        new UnsignedValue("samplingPacketInterval", 1),
                        new UnsignedValue("samplingPacketSpace", 99)));
        assertThat(this.table.lookup(exporter, 4L)).isPresent();

        this.table.accept(exporter, selector, samplerRecord(0));

        assertThat(this.table.lookup(exporter, 4L)).isEmpty();
    }

    /**
     * A stated interval in Selector scope is mirrored exporter-wide, so its withdrawal must reach
     * the mirror too. Otherwise flows naming no Selector keep a multiplier the exporter retracted.
     */
    @Test
    void aSelectorScopedWithdrawalDropsTheExporterWideMirrorToo() throws Exception {
        final var exporter = exporter("192.0.2.1", 0);
        final var selector = List.<Value<?>>of(new UnsignedValue("selectorId", 4));
        this.table.accept(exporter, selector, samplerRecord(1000));
        assertThat(this.table.lookup(exporter)).isPresent();

        this.table.accept(exporter, selector, samplerRecord(0));

        assertThat(this.table.lookup(exporter, 4L)).isEmpty();
        assertThat(this.table.lookup(exporter)).isEmpty();
    }

    /**
     * A Selector that stated nothing exporter-wide withdraws nothing exporter-wide. The unscoped
     * advertisement is an independent statement and stays.
     */
    @Test
    void aSelectorScopedWithdrawalLeavesAnUnscopedAdvertisementAlone() throws Exception {
        final var exporter = exporter("192.0.2.1", 0);
        final var selector = List.<Value<?>>of(new UnsignedValue("selectorId", 7));
        this.table.accept(exporter, List.of(), samplerRecord(1000));
        this.table.accept(exporter, selector,
                List.of(new UnsignedValue("selectorAlgorithm", 1),
                        new UnsignedValue("samplingPacketInterval", 1),
                        new UnsignedValue("samplingPacketSpace", 99)));

        this.table.accept(exporter, selector, samplerRecord(0));

        assertThat(this.table.lookup(exporter, 7L).map(ExporterSamplingTable.AdvertisedRate::interval))
                .contains(1000.0);
        assertThat(this.table.lookup(exporter).map(ExporterSamplingTable.AdvertisedRate::interval))
                .contains(1000.0);
    }

    /** The mirror holds the last Selector's statement; an earlier Selector withdrawing does not touch it. */
    @Test
    void aSelectorScopedWithdrawalLeavesAnotherSelectorsMirrorAlone() throws Exception {
        final var exporter = exporter("192.0.2.1", 0);
        final var eight = List.<Value<?>>of(new UnsignedValue("selectorId", 8));
        final var seven = List.<Value<?>>of(new UnsignedValue("selectorId", 7));
        this.table.accept(exporter, eight, samplerRecord(2000));
        this.table.accept(exporter, seven, samplerRecord(1000));

        this.table.accept(exporter, eight, samplerRecord(0));

        assertThat(this.table.lookup(exporter, 8L).map(ExporterSamplingTable.AdvertisedRate::interval))
                .as("selector 8 falls back to the exporter-wide mirror, which is selector 7's")
                .contains(1000.0);
        assertThat(this.table.lookup(exporter).map(ExporterSamplingTable.AdvertisedRate::interval))
                .contains(1000.0);
    }

    /**
     * A selectorId-scoped record stating an interval of 0 names no algorithm, but it does name a
     * field this table reads. Filing it as unrecognised would hide a withdrawal among the VRF tables.
     */
    @Test
    void aSelectorScopedRecordStatingAnUnusableRateIsRecognisedButUnusable() throws Exception {
        assertThat(this.table.accept(exporter("192.0.2.1", 0),
                List.of(new UnsignedValue("selectorId", 4)),
                samplerRecord(0)))
                .isEqualTo(OptionListener.Verdict.RECOGNISED_BUT_UNUSABLE);
    }

    /**
     * An advertisement naming a sampling algorithm and omitting its parameters: understood, dropped.
     *
     * <p>The sibling of {@link #aFilteringAlgorithmIsRecognisedButUnusable}, on the other side of
     * the same {@code if}. {@code IpfixSelectorReportTest.anIncompletelyStatedAdvertisementTeachesNothing}
     * reaches the branch but observes only that the earlier rate survives.</p>
     */
    @Test
    void anIncompletelyStatedAdvertisementIsRecognisedButUnusable() throws Exception {
        assertThat(this.table.accept(exporter("192.0.2.1", 0), List.of(),
                List.of(new UnsignedValue("selectorAlgorithm", 1))))
                .isEqualTo(OptionListener.Verdict.RECOGNISED_BUT_UNUSABLE);
    }
}
