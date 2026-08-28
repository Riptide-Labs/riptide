/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalCause;
import com.google.common.primitives.UnsignedLong;
import org.riptide.flows.parser.ie.Value;
import org.riptide.flows.parser.ie.values.visitor.StringVisitor;
import org.riptide.flows.parser.ie.values.visitor.UnsignedLongVisitor;
import org.riptide.flows.parser.session.OptionListener;
import org.riptide.flows.parser.session.OptionListener.Verdict;
import org.riptide.flows.parser.session.SessionAdmissionConfig;
import org.riptide.pipeline.ExporterIdentity;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
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

    /**
     * Nested per scope rather than flat on {@code (identity, ifIndex)}, so the {@code ifIndex} half
     * can be bounded on its own.
     *
     * <p>A per-scope cap is what this table actually needs: {@code addOptions} runs once per option
     * <em>data record</em>, several hundred fit in one datagram, and an attacker inside a single
     * admitted scope can walk {@code ifIndex} across 2^32 values. A flat map with one size bound
     * would instead evict across scopes, letting whoever sprays hardest displace a real exporter's
     * interface names — the global-LRU hole {@code SessionAdmission} exists to avoid.
     *
     * <p>The outer bound is belt-and-braces. Reaching {@code addOptions} at all requires a template,
     * and a template requires admission, so the live scope population is already bounded upstream.
     * What that argument does not cover is the retention window: a scope displaced from its
     * admission budget stops receiving records but keeps its inner map until the TTL expires it, so
     * a sustained spray could hold more scopes here than are admitted at any instant. Bounding the
     * outer level too is what makes the documented worst-case product an actual ceiling rather than
     * a steady-state estimate.
     */
    private final Cache<ExporterIdentity, Cache<Integer, IfInfo>> table;

    private final Duration retention;
    private final int maxIfIndexesPerScope;

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
        this.retention = Duration.ofMillis(optionsConfig.getRetentionMs());
        this.maxIfIndexesPerScope = admissionConfig.getMaxIfIndexesPerScope();
        this.table = CacheBuilder.newBuilder()
                .expireAfterWrite(this.retention)
                .maximumSize(scopeCeiling(admissionConfig))
                .build();
        this.recordsConsumed = metrics.meter(MetricRegistry.name("enrichment", "optionInterfaces", "consumed"));
        this.recordsSkipped = metrics.meter(MetricRegistry.name("enrichment", "optionInterfaces", "skipped"));
        this.recordsRejected = metrics.meter(MetricRegistry.name("enrichment", "optionInterfaces", "rejected"));
    }

    /**
     * The most scopes that can be admitted anywhere, clamped so a large configuration cannot
     * overflow the {@code long} Guava wants.
     */
    private static long scopeCeiling(final SessionAdmissionConfig config) {
        final long sources = Math.max(1, config.getMaxSources());
        final long scopes = Math.max(1, config.getMaxScopesPerSource());
        return sources > Long.MAX_VALUE / scopes ? Long.MAX_VALUE : sources * scopes;
    }

    @Override
    public Verdict accept(final ExporterIdentity identity,
            final Collection<Value<?>> scopes, final List<Value<?>> values) {
        final String name = string(values, NAME_FIELDS);
        final String description = string(values, DESCRIPTION_FIELDS);
        if (name == null && description == null) {
            // Neither a name nor a description: not this table's shape at all.
            return Verdict.UNRECOGNISED; // sampler/VRF/app tables, …
        }

        Integer ifIndex = unsigned(scopes, IFINDEX_SCOPES);
        if (ifIndex == null || ifIndex == 0) {
            // a zero scope value is as good as none: fall through to the fields
            ifIndex = unsigned(values, IFINDEX_FIELDS);
        }
        if (ifIndex == null || ifIndex == 0) {
            this.recordsSkipped.mark();
            // Recognised and unusable, which is a different fact from unrecognised (#599). riptide
            // understood this record and still got nothing from it — the state worth an operator's
            // attention. Reporting it as unrecognised would bury it among the VRF and application
            // tables nobody is meant to read.
            return Verdict.RECOGNISED_BUT_UNUSABLE;
        }

        final Cache<Integer, IfInfo> forScope = scopeTable(identity);
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

    /**
     * This scope's interface map, created on first use.
     *
     * <p>{@code maximumSize} on the inner cache is the per-scope bound, and Guava's eviction is LRU
     * within that cache alone — so a scope that sprays displaces only its own entries. The explicit
     * size check in {@link #accept} sits in front of it because Guava evicts lazily and would
     * otherwise let the map run over the cap between maintenance passes without ever marking the
     * meter.
     */
    private Cache<Integer, IfInfo> scopeTable(final ExporterIdentity identity) {
        try {
            return this.table.get(identity, () -> CacheBuilder.newBuilder()
                    .expireAfterWrite(this.retention)
                    .maximumSize(this.maxIfIndexesPerScope)
                    // Only SIZE is counted. Expiry is the table working as designed — exporters
                    // re-send their option tables — whereas a size eviction is the cap biting, and
                    // is the one an operator needs to tell an attack from a cap set too low.
                    .<Integer, IfInfo>removalListener(notification -> {
                        if (notification.getCause() == RemovalCause.SIZE) {
                            this.recordsRejected.mark();
                        }
                    })
                    .build());
        } catch (final ExecutionException e) {
            // The loader is a plain builder call and throws nothing checked; Guava still declares it.
            throw new IllegalStateException("interface table for " + identity + " could not be created", e);
        }
    }

    /** Approximate and cheap; exactly {@code true} when nothing was ever inserted. */
    public boolean isEmpty() {
        return this.table.size() == 0;
    }

    public Optional<IfInfo> lookup(final ExporterIdentity identity, final int ifIndex) {
        final Cache<Integer, IfInfo> forScope = this.table.getIfPresent(identity);
        return forScope == null ? Optional.empty() : Optional.ofNullable(forScope.getIfPresent(ifIndex));
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
