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
     * A record this table consumed is reported as claimed, even when it states a rate of exactly 1.
     *
     * <p>The trap #599 had to avoid. {@code acceptSamplerOptions} already returned a boolean, but it
     * meant "states a rate above 1" — a control signal deciding whether to keep reading the record —
     * not "I took this". They disagree on exactly this input: a rate of 1 means "not sampling",
     * which is an answer, so the record is stored and consumed while the old boolean said
     * {@code false}. Reusing it as the claim verdict would have reported a record riptide acted on
     * as claimed by nobody, and the unclaimed meter would have climbed on healthy exporters.</p>
     */
    @Test
    void aSamplerRecordStatingRateOneIsStillClaimed() throws Exception {
        assertThat(this.table.accept(exporter("192.0.2.1", 0), List.of(), samplerRecord(1)))
                .as("a rate of 1 is an answer, not a shrug: the record was stored, so it was claimed")
                .isTrue();
    }

    /** A record no field of which this table understands is reported as unclaimed. */
    @Test
    void aRecordThisTableDoesNotRecogniseIsNotClaimed() throws Exception {
        assertThat(this.table.accept(exporter("192.0.2.1", 0), List.of(),
                List.of(new UnsignedValue("INPUT_SNMP", 7))))
                .as("an interface record states no rate and no selector algorithm, so this table"
                        + " takes nothing from it")
                .isFalse();
    }
}
