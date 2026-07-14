/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.primitives.UnsignedLong;
import org.riptide.flows.parser.ie.Value;
import org.riptide.flows.parser.ie.values.visitor.StringVisitor;
import org.riptide.flows.parser.ie.values.visitor.UnsignedLongVisitor;
import org.riptide.flows.parser.session.OptionListener;
import org.riptide.pipeline.ExporterIdentity;
import org.riptide.utils.Tuple;
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
 */
@Component
public class ExporterInterfaceTable implements OptionListener {

    private static final List<String> NAME_FIELDS = List.of("IF_NAME", "interfaceName");
    private static final List<String> DESCRIPTION_FIELDS = List.of("IF_DESC", "interfaceDescription");
    // the table is direction-neutral, so egress-keyed variants are accepted too
    private static final List<String> IFINDEX_SCOPES = List.of("SCOPE:INTERFACE", "ingressInterface", "egressInterface");
    private static final List<String> IFINDEX_FIELDS = List.of("INPUT_SNMP", "ingressInterface", "OUTPUT_SNMP", "egressInterface");

    private final Cache<Tuple<ExporterIdentity, Integer>, IfInfo> table;

    private final Meter recordsConsumed;
    private final Meter recordsSkipped;

    public ExporterInterfaceTable(final SnmpCacheConfig cacheConfig, final MetricRegistry metrics) {
        this.table = CacheBuilder.newBuilder()
                // 2x the poll-cache retention: exporters re-send option tables at
                // roughly the same cadence (Cisco default 600 s == our default
                // retention), so a 1x TTL would race every refresh and one lost
                // option packet would unenrich flows for a full cycle
                .expireAfterWrite(Duration.ofMillis(2 * cacheConfig.getRetentionMs()))
                .build();
        this.recordsConsumed = metrics.meter(MetricRegistry.name("enrichment", "optionInterfaces", "consumed"));
        this.recordsSkipped = metrics.meter(MetricRegistry.name("enrichment", "optionInterfaces", "skipped"));
    }

    @Override
    public void accept(final ExporterIdentity identity, final Collection<Value<?>> scopes, final List<Value<?>> values) {
        final String name = string(values, NAME_FIELDS);
        final String description = string(values, DESCRIPTION_FIELDS);
        if (name == null && description == null) {
            return; // not an interface option record (sampler/VRF/app tables, …)
        }

        Integer ifIndex = unsigned(scopes, IFINDEX_SCOPES);
        if (ifIndex == null || ifIndex == 0) {
            // a zero scope value is as good as none: fall through to the fields
            ifIndex = unsigned(values, IFINDEX_FIELDS);
        }
        if (ifIndex == null || ifIndex == 0) {
            this.recordsSkipped.mark();
            return;
        }

        // per-field merge: exporters may split name and description over separate
        // option tables (e.g. an interface-scoped table plus the IOS-XR style one);
        // the fresh record pins its fields, the existing entry fills the rest
        final Tuple<ExporterIdentity, Integer> key = Tuple.of(identity, ifIndex);
        this.table.put(key, IfInfo.merge(new IfInfo(name, description, null), this.table.getIfPresent(key)));
        this.recordsConsumed.mark();
    }

    /** Approximate and cheap; exactly {@code true} when nothing was ever inserted. */
    public boolean isEmpty() {
        return this.table.size() == 0;
    }

    public Optional<IfInfo> lookup(final ExporterIdentity identity, final int ifIndex) {
        return Optional.ofNullable(this.table.getIfPresent(Tuple.of(identity, ifIndex)));
    }

    private static String string(final Collection<Value<?>> values, final List<String> names) {
        for (final Value<?> value : values) {
            if (names.contains(value.getName())) {
                final String s = value.accept(new StringVisitor());
                if (s != null) {
                    // v9 strings are fixed-width and NUL-padded on the wire
                    final String trimmed = s.replace("\0", "").trim();
                    return trimmed.isEmpty() ? null : trimmed;
                }
            }
        }
        return null;
    }

    private static Integer unsigned(final Collection<Value<?>> values, final List<String> names) {
        for (final Value<?> value : values) {
            if (names.contains(value.getName())) {
                final UnsignedLong u = value.accept(new UnsignedLongVisitor());
                if (u != null) {
                    return u.intValue();
                }
            }
        }
        return null;
    }
}
