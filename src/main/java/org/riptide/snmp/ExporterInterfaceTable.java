/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.google.common.cache.Cache;
import org.riptide.flows.parser.ie.Value;
import org.riptide.flows.parser.session.ExporterScopedTable;
import org.riptide.flows.parser.session.OptionListener;
import org.riptide.flows.parser.session.OptionListener.Verdict;
import org.riptide.flows.parser.session.OptionTables;
import org.riptide.flows.parser.session.SessionAdmissionConfig;
import org.riptide.pipeline.ExporterIdentity;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.time.Duration;

/**
 * Interface names pushed by exporters as v9/IPFIX option records — the enrichment
 * ladder's zero-config rung between static mappings and live SNMP. Fed by the option
 * tap ({@link OptionListener}); entries expire on the same retention as the SNMP
 * cache (exporters re-send option tables periodically; Cisco defaults to 600 s).
 *
 * <p>Recognized shapes (see change design, verified against captured fixtures):
 * ifIndex in the scope (v9 {@code SCOPE:INTERFACE}, IPFIX {@code ingressInterface})
 * or — the shape real Cisco IOS-XR exporters use — a system scope with the ifIndex as
 * an option <em>field</em>. The trigger is IE 82/83 present in the fields: the ASR9k
 * interface table carries only {@code IF_DESC}(83), never IE 82.</p>
 *
 * <p>Description (83) lands in the {@code alias} slot: IANA anchors it to ifDescr but
 * its own examples include ifAlias-style content; per-field authority in
 * {@link IfInfo#optionsThenSnmp} lets a real SNMP ifAlias win over it.</p>
 *
 * <p>Storage and lookup are {@link ExporterScopedTable}'s: one inner cache per exporter identity,
 * with a fallback from the exact identity to the device address for exporters that send their
 * tables and their flow records under different observation domains.</p>
 */
@Component
public class ExporterInterfaceTable implements OptionListener {

    private static final List<String> NAME_FIELDS = List.of("IF_NAME", "interfaceName");
    private static final List<String> DESCRIPTION_FIELDS = List.of("IF_DESC", "interfaceDescription");
    // the table is direction-neutral, so egress-keyed variants are accepted too
    private static final List<String> IFINDEX_SCOPES = List.of("SCOPE:INTERFACE", "ingressInterface", "egressInterface");
    private static final List<String> IFINDEX_FIELDS = List.of("INPUT_SNMP", "ingressInterface", "OUTPUT_SNMP", "egressInterface");

    /**
     * Why this table needs the per-scope cap {@link ExporterScopedTable} gives it: {@code addOptions}
     * runs once per option <em>data record</em>, several hundred fit in one datagram, and an
     * attacker inside a single admitted scope can walk {@code ifIndex} across 2^32 values. The
     * nesting, both bounds and the device-address index are all {@link ExporterScopedTable}'s.
     */
    private final ExporterScopedTable<Integer, IfInfo> table;

    private final Meter recordsConsumed;
    private final Meter recordsSkipped;
    /**
     * Interface entries evicted because a scope hit its cap. Degrade-only, so this is a meter and
     * not a warning: static pins and live SNMP still resolve the interface, and the flow is still
     * emitted. Watch it to tell an attack from a cap set too low for a large chassis.
     */
    private final Meter recordsRejected;

    public ExporterInterfaceTable(final SnmpOptionsConfig optionsConfig,
                                  final SessionAdmissionConfig admissionConfig,
                                  final MetricRegistry metrics) {
        // sized against how often exporters re-send option tables, not against how often
        // riptide polls — see SnmpOptionsConfig for why those stopped being the same thing
        admissionConfig.validate();
        this.recordsConsumed = metrics.meter(MetricRegistry.name("enrichment", "optionInterfaces", "consumed"));
        this.recordsSkipped = metrics.meter(MetricRegistry.name("enrichment", "optionInterfaces", "skipped"));
        this.recordsRejected = metrics.meter(MetricRegistry.name("enrichment", "optionInterfaces", "rejected"));
        this.table = new ExporterScopedTable<>(Duration.ofMillis(optionsConfig.getRetentionMs()),
                OptionTables.scopeCeiling(admissionConfig), admissionConfig.getMaxIfIndexesPerScope(),
                this.recordsRejected::mark);
    }

    @Override
    public Verdict accept(final ExporterIdentity identity,
            final Collection<Value<?>> scopes, final List<Value<?>> values) {
        final String name = OptionTables.string(values, NAME_FIELDS);
        final String description = OptionTables.string(values, DESCRIPTION_FIELDS);
        if (name == null && description == null) {
            // Neither a name nor a description: not this table's shape at all.
            // sampler, VRF and application tables: another consumer's shape, or nobody's
            return Verdict.UNRECOGNISED;
        }

        Integer ifIndex = toIfIndex(OptionTables.unsigned(scopes, IFINDEX_SCOPES));
        if (ifIndex == null || ifIndex == 0) {
            // a zero scope value is as good as none: fall through to the fields
            ifIndex = toIfIndex(OptionTables.unsigned(values, IFINDEX_FIELDS));
        }
        if (ifIndex == null || ifIndex == 0) {
            this.recordsSkipped.mark();
            // Recognised and unusable, which is a different fact from unrecognised (#599). riptide
            // understood this record and still got nothing from it — the state worth an operator's
            // attention. Reporting it as unrecognised would bury it among the VRF tables and other
            // shapes nobody consumes.
            return Verdict.RECOGNISED_BUT_UNUSABLE;
        }

        final Cache<Integer, IfInfo> forScope = this.table.scope(identity);
        // per-field merge: exporters may split name and description over separate
        // option tables (e.g. an interface-scoped table plus the IOS-XR style one);
        // the fresh record pins its fields, the existing entry fills the rest
        final IfInfo existing = forScope.getIfPresent(ifIndex);
        // Always written, never refused. The cap is enforced by evicting this scope's
        // least-recently-used interface instead: refusing the new entry would leave a device with
        // more interfaces than the cap permanently blind to whichever ones it happened to mention
        // last, while eviction keeps the window over the interfaces actually carrying traffic.
        forScope.put(ifIndex, IfInfo.merge(new IfInfo(name, description, null), existing));
        this.recordsConsumed.mark();
        return Verdict.CLAIMED;
    }

    /** Approximate and cheap; exactly {@code true} when nothing was ever inserted. */
    public boolean isEmpty() {
        return this.table.isEmpty();
    }

    /** Exact identity first, then, only if it has no table at all, another domain of the device. */
    public Optional<IfInfo> lookup(final ExporterIdentity identity, final int ifIndex) {
        return this.table.lookup(identity, ifIndex);
    }

    private static Integer toIfIndex(final Long unsigned) {
        return unsigned == null ? null : unsigned.intValue();
    }
}
