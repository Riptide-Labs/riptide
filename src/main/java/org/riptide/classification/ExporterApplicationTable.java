/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.classification;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.google.common.cache.Cache;
import org.riptide.flows.parser.ie.Value;
import org.riptide.flows.parser.ie.values.ApplicationIdValue;
import org.riptide.flows.parser.session.ExporterScopedTable;
import org.riptide.flows.parser.session.OptionListener;
import org.riptide.flows.parser.session.OptionListener.Verdict;
import org.riptide.flows.parser.session.OptionTables;
import org.riptide.flows.parser.session.SessionAdmissionConfig;
import org.riptide.pipeline.ExporterIdentity;
import org.riptide.snmp.SnmpOptionsConfig;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Application names pushed by exporters as IPFIX option records (RFC 6759 §4.3, Cisco's
 * {@code option application-table}): the enrichment ladder's exporter-pushed rung for the
 * {@code application} column, above the port rules. Fed by the option tap
 * ({@link OptionListener}); entries expire on the same retention as the interface table, since
 * exporters re-send both tables on the same cadence.
 *
 * <p>Recognised shape: an {@code applicationName} (96) or {@code applicationDescription} (94)
 * field, with the packed {@code applicationId} (95) in the scope or, failing that, in the fields.
 * Keyed like {@link org.riptide.snmp.ExporterInterfaceTable}: one inner cache per exporter
 * identity, capped per scope at {@link #MAX_APPLICATIONS_PER_SCOPE} so a sprayed table displaces
 * only its own entries.</p>
 *
 * <p>Storage and lookup are {@link ExporterScopedTable}'s, so lookups fall back from the exact
 * identity to the device address.
 * A Catalyst 8000V sends its option tables under one observation domain and its flow records under
 * another; RFC 6759 scopes the application table to the exporting process, which is what the
 * address fallback expresses.</p>
 */
@Component
public class ExporterApplicationTable implements OptionListener {

    private static final List<String> ID_FIELDS = List.of(ApplicationIdValue.NAME);
    private static final List<String> NAME_FIELDS = List.of("applicationName");
    private static final List<String> DESCRIPTION_FIELDS = List.of("applicationDescription");

    /**
     * Application ids retained per scope identity, fixed rather than shared with the interface
     * table's {@code max-ifindexes-per-scope}.
     *
     * <p>A full NBAR2 protocol pack plus the IANA L3/L4 rows the option table also carries is a few
     * thousand ids; a Catalyst 8000V sends 1,560 rows in a single refresh, IANA engines first and
     * the Cisco engine last. At the interface table's cap of 1,024 the rows naming http, dns and
     * icmp were evicted before the first flow arrived, so every such flow fell back to the port
     * rules (replay gate, 2026-09-21). The {@code applicationId} space is 8 bits of engine and 24
     * of selector, so an unbounded cache is still not an option: a bound is required, it is just
     * not the interface table's bound. The outer per-source ceiling still comes from
     * {@link SessionAdmissionConfig} via {@link OptionTables#scopeCeiling}.</p>
     */
    private static final int MAX_APPLICATIONS_PER_SCOPE = 16_384;

    /**
     * Longest {@code applicationName} stored, counted the way Java counts a {@code String}'s
     * {@code length()}: in UTF-16 code units, not the bytes the name occupied on the wire. A longer
     * one is refused outright rather than truncated.
     *
     * <p>{@code application} is a sort key on the LowCardinality rollups
     * ({@code flows_by_application_1m}, {@code flows_by_conversation_1m}), and a wire
     * {@code StringValue} carries up to 65,535 bytes. Without this an exporter, or anything that
     * can forge one packet from its address, writes arbitrary text into a rollup dimension. A real
     * NBAR2 name is at most 24 bytes, decoded to well under 64 UTF-16 code units, so every legitimate
     * name clears this cap with room to spare; the byte figure on the wire stops mattering once the
     * value has been decoded into the {@code String} this cap measures. Storing a truncated prefix
     * of an over-long name would put the same fabricated vocabulary in the column, just shorter.</p>
     */
    private static final int MAX_NAME_LENGTH = 64;

    /**
     * Longest {@code applicationDescription} stored; a longer one is truncated to it.
     *
     * <p>Truncated rather than refused because a description is never a dimension: it has no column
     * today and is not a sort key in any rollup, so an over-long one costs memory and nothing else.
     * Refusing the row over it would throw away the name, which is the field that matters.</p>
     */
    private static final int MAX_DESCRIPTION_LENGTH = 255;

    private final ExporterScopedTable<Long, ApplicationInfo> table;

    private final Meter recordsConsumed;
    private final Meter recordsSkipped;
    private final Meter recordsRejected;

    public ExporterApplicationTable(final SnmpOptionsConfig optionsConfig,
                                    final SessionAdmissionConfig admissionConfig,
                                    final MetricRegistry metrics) {
        admissionConfig.validate();
        this.recordsConsumed = metrics.meter(MetricRegistry.name("enrichment", "optionApplications", "consumed"));
        this.recordsSkipped = metrics.meter(MetricRegistry.name("enrichment", "optionApplications", "skipped"));
        this.recordsRejected = metrics.meter(MetricRegistry.name("enrichment", "optionApplications", "rejected"));
        this.table = new ExporterScopedTable<>(Duration.ofMillis(optionsConfig.getRetentionMs()),
                OptionTables.scopeCeiling(admissionConfig), MAX_APPLICATIONS_PER_SCOPE,
                this.recordsRejected::mark);
    }

    @Override
    public Verdict accept(final ExporterIdentity identity,
                          final Collection<Value<?>> scopes, final List<Value<?>> values) {
        final String name = OptionTables.string(values, NAME_FIELDS);
        final String rawDescription = OptionTables.string(values, DESCRIPTION_FIELDS);
        if (name == null && rawDescription == null) {
            return Verdict.UNRECOGNISED; // interface, sampler, VRF tables, …
        }
        if (name != null && name.length() > MAX_NAME_LENGTH) {
            this.recordsSkipped.mark();
            return Verdict.RECOGNISED_BUT_UNUSABLE;
        }
        final String description = rawDescription == null || rawDescription.length() <= MAX_DESCRIPTION_LENGTH
                ? rawDescription
                : rawDescription.substring(0, MAX_DESCRIPTION_LENGTH);

        Long applicationId = OptionTables.unsigned(scopes, ID_FIELDS);
        if (applicationId == null || applicationId == 0L) {
            applicationId = OptionTables.unsigned(values, ID_FIELDS);
        }
        if (applicationId == null || applicationId == 0L) {
            this.recordsSkipped.mark();
            return Verdict.RECOGNISED_BUT_UNUSABLE;
        }

        final Cache<Long, ApplicationInfo> forScope = this.table.scope(identity);
        final ApplicationInfo existing = forScope.getIfPresent(applicationId);
        forScope.put(applicationId, ApplicationInfo.merge(new ApplicationInfo(name, description), existing));
        this.recordsConsumed.mark();
        return Verdict.CLAIMED;
    }

    /** Approximate and cheap; exactly {@code true} when nothing was ever inserted. */
    public boolean isEmpty() {
        return this.table.isEmpty();
    }

    /** Exact identity first, then, only if it has no table at all, another domain of the device. */
    public Optional<ApplicationInfo> lookup(final ExporterIdentity identity, final long applicationId) {
        return this.table.lookup(identity, applicationId);
    }
}
