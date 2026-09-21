/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.classification;

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
import org.riptide.snmp.SnmpOptionsConfig;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

/**
 * Application names pushed by exporters as IPFIX option records (RFC 6759 §4.3, Cisco's
 * {@code option application-table}): the enrichment ladder's exporter-pushed rung for the
 * {@code application} column, above the port rules. Fed by the option tap
 * ({@link OptionListener}); entries expire on the same retention as the interface table, since
 * exporters re-send both tables on the same cadence.
 *
 * <p>Recognised shape: an {@code applicationName} (96) or {@code applicationDescription} (94)
 * field, with the packed {@code applicationId} (95) in the scope or, failing that, in the fields.
 * Keyed and bounded exactly like {@link org.riptide.snmp.ExporterInterfaceTable}: one inner cache
 * per exporter identity, capped per scope, so a sprayed table displaces only its own entries.</p>
 *
 * <p>{@link #lookup} tries the exact identity first and then any observation domain of the same
 * device address. A Catalyst 8000V sends its option tables under one observation domain and its
 * flow records under another; RFC 6759 scopes the application table to the exporting process,
 * which is what the address fallback expresses.</p>
 */
@Component
public class ExporterApplicationTable implements OptionListener {

    private static final List<String> ID_FIELDS = List.of("applicationId");
    private static final List<String> NAME_FIELDS = List.of("applicationName");
    private static final List<String> DESCRIPTION_FIELDS = List.of("applicationDescription");

    private final Cache<ExporterIdentity, Cache<Long, ApplicationInfo>> table;

    private final Duration retention;
    private final int maxIdsPerScope;

    private final Meter recordsConsumed;
    private final Meter recordsSkipped;
    private final Meter recordsRejected;

    public ExporterApplicationTable(final SnmpOptionsConfig optionsConfig,
                                    final SessionAdmissionConfig admissionConfig,
                                    final MetricRegistry metrics) {
        admissionConfig.validate();
        this.retention = Duration.ofMillis(optionsConfig.getRetentionMs());
        // The interface table's per-scope cap, reused rather than duplicated as a new key: both
        // tables are one option record per row, and the same chassis sends both.
        this.maxIdsPerScope = admissionConfig.getMaxIfIndexesPerScope();
        this.table = CacheBuilder.newBuilder()
                .expireAfterWrite(this.retention)
                .maximumSize(scopeCeiling(admissionConfig))
                .build();
        this.recordsConsumed = metrics.meter(MetricRegistry.name("enrichment", "optionApplications", "consumed"));
        this.recordsSkipped = metrics.meter(MetricRegistry.name("enrichment", "optionApplications", "skipped"));
        this.recordsRejected = metrics.meter(MetricRegistry.name("enrichment", "optionApplications", "rejected"));
    }

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
            return Verdict.UNRECOGNISED; // interface, sampler, VRF tables, …
        }

        Long applicationId = unsigned(scopes, ID_FIELDS);
        if (applicationId == null || applicationId == 0L) {
            applicationId = unsigned(values, ID_FIELDS);
        }
        if (applicationId == null || applicationId == 0L) {
            this.recordsSkipped.mark();
            return Verdict.RECOGNISED_BUT_UNUSABLE;
        }

        final Cache<Long, ApplicationInfo> forScope = scopeTable(identity);
        final ApplicationInfo existing = forScope.getIfPresent(applicationId);
        forScope.put(applicationId, ApplicationInfo.merge(new ApplicationInfo(name, description), existing));
        this.recordsConsumed.mark();
        return Verdict.CLAIMED;
    }

    private Cache<Long, ApplicationInfo> scopeTable(final ExporterIdentity identity) {
        try {
            return this.table.get(identity, () -> CacheBuilder.newBuilder()
                    .expireAfterWrite(this.retention)
                    .maximumSize(this.maxIdsPerScope)
                    .<Long, ApplicationInfo>removalListener(notification -> {
                        if (notification.getCause() == RemovalCause.SIZE) {
                            this.recordsRejected.mark();
                        }
                    })
                    .build());
        } catch (final ExecutionException e) {
            throw new IllegalStateException("application table for " + identity + " could not be created", e);
        }
    }

    /** Approximate and cheap; exactly {@code true} when nothing was ever inserted. */
    public boolean isEmpty() {
        return this.table.size() == 0;
    }

    /** Exact identity first, then any observation domain of the same device address. */
    public Optional<ApplicationInfo> lookup(final ExporterIdentity identity, final long applicationId) {
        final Cache<Long, ApplicationInfo> exact = this.table.getIfPresent(identity);
        if (exact != null) {
            final ApplicationInfo info = exact.getIfPresent(applicationId);
            if (info != null) {
                return Optional.of(info);
            }
        }
        for (final var entry : this.table.asMap().entrySet()) {
            if (!entry.getKey().equals(identity)
                    && entry.getKey().deviceAddress().equals(identity.deviceAddress())) {
                final ApplicationInfo info = entry.getValue().getIfPresent(applicationId);
                if (info != null) {
                    return Optional.of(info);
                }
            }
        }
        return Optional.empty();
    }

    private static String string(final Collection<Value<?>> values, final List<String> names) {
        for (final Value<?> value : values) {
            if (names.contains(value.getName())) {
                final String s = value.accept(new StringVisitor());
                if (s != null) {
                    // fixed-width and NUL-padded on the wire (24 and 55 bytes on a c8000v)
                    final String trimmed = s.replace("\0", "").trim();
                    return trimmed.isEmpty() ? null : trimmed;
                }
            }
        }
        return null;
    }

    private static Long unsigned(final Collection<Value<?>> values, final List<String> names) {
        for (final Value<?> value : values) {
            if (names.contains(value.getName())) {
                final UnsignedLong u = value.accept(new UnsignedLongVisitor());
                if (u != null) {
                    return u.longValue();
                }
            }
        }
        return null;
    }
}
