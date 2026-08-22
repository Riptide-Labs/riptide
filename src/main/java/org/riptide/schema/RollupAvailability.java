/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.schema;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which rollups the query path may use, decided once at startup by {@link RollupShapeCheck} (#470).
 *
 * <p>Detection without a routing consequence would only write the wrong answer down in a log. A
 * drifted rollup still answers every query wider than the router's threshold, so the fallback to
 * raw {@code flows} is what actually makes the wrong answer unreachable.</p>
 *
 * <p>Static, and holding one immutable set, because the router is static and the answer is a
 * constant: a schema does not change under a running collector. Re-reading {@code system.tables}
 * per route would be a per-query cost for a value fixed at startup.</p>
 *
 * <p>Only <em>drifted</em> rollups are withheld. A rollup that could not be verified is not thereby
 * known to be wrong, and declining it would silently degrade every query on a deployment whose only
 * fault is a missing grant.</p>
 */
public final class RollupAvailability {

    private RollupAvailability() {
    }

    private static volatile Set<String> drifted = Set.of();

    /** Rollups already refused once, so the query path does not repeat itself on every query. */
    private static final Set<String> REFUSED = ConcurrentHashMap.newKeySet();

    /** Record the unusable rollups' target table names. Called once, at startup. */
    public static void recordDrifted(final Collection<String> rollupTables) {
        drifted = Set.copyOf(rollupTables);
        // A new verdict earns a fresh round of notification: a rollup repaired and re-declared
        // usable, then unusable again, must say so rather than inherit the old silence.
        REFUSED.clear();
    }

    /**
     * Whether this is the first time the query path has refused {@code rollup} under the current
     * verdict — the caller logs only then.
     *
     * <p>Without this the router emits a warning per query, forever, for a condition already
     * reported once at startup. On a dashboard- or MCP-driven deployment that is continuous log
     * volume that buries everything else.</p>
     */
    public static boolean firstRefusalOf(final String rollup) {
        return REFUSED.add(rollup);
    }

    /**
     * Whether a rollup target may answer a query.
     *
     * @param table a qualified or bare rollup target table name
     */
    public static boolean usable(final String table) {
        if (table == null) {
            return false;
        }
        return drifted.stream().noneMatch(rollup -> table.equals(rollup) || table.endsWith("." + rollup));
    }
}
