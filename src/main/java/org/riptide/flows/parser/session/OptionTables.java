/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser.session;

import com.google.common.primitives.UnsignedLong;
import org.riptide.flows.parser.ie.Value;
import org.riptide.flows.parser.ie.values.visitor.StringVisitor;
import org.riptide.flows.parser.ie.values.visitor.UnsignedLongVisitor;
import org.riptide.pipeline.ExporterIdentity;

import java.util.Collection;
import java.util.List;

/**
 * Shared sizing and parsing behaviour for exporter-scoped option tables: those keyed on an
 * {@link ExporterIdentity} — one inner cache per exporter, holding rows pushed as NetFlow v9 /
 * IPFIX option records (interface names, application names, and any table shaped the same way).
 *
 * <p>Storage and lookup live in {@link ExporterScopedTable}, which owns the outer cache and the
 * device-address index the fallback needs.</p>
 */
public final class OptionTables {

    private OptionTables() {
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
