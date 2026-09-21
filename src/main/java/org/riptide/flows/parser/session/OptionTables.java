/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser.session;

import com.google.common.cache.Cache;
import com.google.common.primitives.UnsignedLong;
import org.riptide.flows.parser.ie.Value;
import org.riptide.flows.parser.ie.values.visitor.StringVisitor;
import org.riptide.flows.parser.ie.values.visitor.UnsignedLongVisitor;
import org.riptide.pipeline.ExporterIdentity;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Shared lookup and parsing behaviour for exporter-scoped option tables: those keyed on an
 * {@link ExporterIdentity} — one inner cache per exporter, holding rows pushed as NetFlow v9 /
 * IPFIX option records (interface names, application names, and any table shaped the same way).
 *
 * <p>Lookups fall back from the exact identity to the device address because the option table and
 * the flow records that reference it do not always share an observation domain. A Catalyst 8000V
 * sends its option tables under one observation domain and its flow records under another; RFC
 * 6759 and the interface table's own IANA scope both anchor the table to the exporting process,
 * not to a particular domain, so the device address is what the fallback expresses.</p>
 */
public final class OptionTables {

    private OptionTables() {
    }

    /**
     * Exact identity first, then any other entry whose {@link ExporterIdentity#deviceAddress()}
     * equals the identity's; never another device.
     */
    public static <K, V> Optional<V> lookup(final Cache<ExporterIdentity, Cache<K, V>> table,
            final ExporterIdentity identity, final K key) {
        final Cache<K, V> exact = table.getIfPresent(identity);
        if (exact != null) {
            final V value = exact.getIfPresent(key);
            if (value != null) {
                return Optional.of(value);
            }
        }
        for (final var entry : table.asMap().entrySet()) {
            if (!entry.getKey().equals(identity)
                    && entry.getKey().deviceAddress().equals(identity.deviceAddress())) {
                final V value = entry.getValue().getIfPresent(key);
                if (value != null) {
                    return Optional.of(value);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * The most scopes that can be admitted anywhere, clamped so a large configuration cannot
     * overflow the {@code long} Guava wants.
     */
    public static long scopeCeiling(final SessionAdmissionConfig config) {
        final long sources = Math.max(1, config.getMaxSources());
        final long scopes = Math.max(1, config.getMaxScopesPerSource());
        return sources > Long.MAX_VALUE / scopes ? Long.MAX_VALUE : sources * scopes;
    }

    /** NUL-stripped and trimmed; {@code null} when the field is absent or empty on the wire. */
    public static String string(final Collection<Value<?>> values, final List<String> names) {
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

    /** {@code null} when none of {@code names} is present. */
    public static Long unsigned(final Collection<Value<?>> values, final List<String> names) {
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
