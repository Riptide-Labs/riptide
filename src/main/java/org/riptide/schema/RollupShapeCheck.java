/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Compares each rollup's live shape against the shape the running version intends (#470).
 *
 * <p>{@code CREATE MATERIALIZED VIEW IF NOT EXISTS} no-ops against a view that already exists, so a
 * deployment that has ever started riptide keeps its original rollup shape forever. A new dimension
 * or measure reaches a fresh install and not an upgraded one, and nothing fails or logs. This class
 * is the "at minimum, notice" floor: it reports, and repairs nothing.</p>
 *
 * <p>Deliberately a pure function over observed state rather than something holding a connection.
 * The interesting cases — a view whose expression changed but whose column names did not, a role
 * that cannot see the view at all — are then reachable in a unit test instead of only through a
 * live server.</p>
 *
 * <p><b>Two comparisons, because neither alone is sufficient.</b> A column-set comparison cannot see
 * a corrected aggregate, which keeps its column name. A SELECT comparison cannot see a target table
 * missing a column the view does not yet populate.</p>
 */
public final class RollupShapeCheck {

    private RollupShapeCheck() {
    }

    /** What is known about one rollup after comparison. */
    public enum Status {
        /** Live shape matches what this version intends. */
        MATCHES,
        /** Live shape differs. The rollup answers queries from a shape this version did not write. */
        DRIFTED,
        /** Could not be determined — see {@link Result#detail()}. Not evidence of drift. */
        UNVERIFIABLE
    }

    /**
     * One rollup's verdict. {@code detail} is operator-facing: it names what differs, or what could
     * not be read and how to grant it.
     */
    public record Result(String rollup, Status status, String detail) {

        public boolean drifted() {
            return this.status == Status.DRIFTED;
        }
    }

    /** Backticks and line breaks are the server's formatting, not the query's meaning. */
    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    /**
     * Compare every rollup against the live state read from the server.
     *
     * @param database       the configured database, so the intended SELECT qualifies its source the
     *                       same way the emitted DDL does
     * @param liveSelects    {@code <rollup>_mv} to its {@code system.tables.as_select}. A rollup
     *                       absent from this map was <em>not visible</em>, which is not the same as
     *                       missing — see {@link #normalise}.
     * @param liveColumns    rollup target table to its column names, from {@code system.columns}
     */
    public static List<Result> compare(final String database,
            final Map<String, String> liveSelects,
            final Map<String, Set<String>> liveColumns) {
        final Map<String, String> intendedSelects = FlowsSchema.rollupSelects(database);
        final List<Result> results = new ArrayList<>();
        for (final Map.Entry<String, List<String>> intended : FlowsSchema.rollupColumns().entrySet()) {
            results.add(compareOne(intended.getKey(), intended.getValue(),
                    intendedSelects.get(intended.getKey()), liveSelects, liveColumns));
        }
        return List.copyOf(results);
    }

    private static Result compareOne(final String rollup,
            final List<String> intendedColumns,
            final String intendedSelect,
            final Map<String, String> liveSelects,
            final Map<String, Set<String>> liveColumns) {
        final Set<String> live = liveColumns.get(rollup);
        if (live == null) {
            return new Result(rollup, Status.UNVERIFIABLE,
                    "target table is not visible to the connecting user — it is absent, or the user "
                            + "holds no grant on it");
        }

        // Column drift is decided first and independently of whether the view can be read: it is
        // verified either way, and reporting "cannot verify" while holding proof of drift would
        // bury the finding.
        final Set<String> missing = new TreeSet<>(intendedColumns);
        missing.removeAll(live);
        final Set<String> unexpected = new TreeSet<>(live);
        intendedColumns.forEach(unexpected::remove);
        if (!missing.isEmpty() || !unexpected.isEmpty()) {
            // Reported separately because they mean different things: missing is this version
            // expecting a column the table never got, unexpected is the table carrying one this
            // version stopped writing.
            final StringBuilder detail = new StringBuilder("target table columns differ —");
            if (!missing.isEmpty()) {
                detail.append(" missing ").append(missing);
            }
            if (!unexpected.isEmpty()) {
                detail.append(" unexpected ").append(unexpected);
            }
            return new Result(rollup, Status.DRIFTED, detail.toString());
        }

        final String mv = rollup + "_mv";
        final String liveSelect = liveSelects.get(mv);
        if (liveSelect == null) {
            // ClickHouse filters system.tables by access rather than refusing the query, so an
            // ungranted view and an absent one are the same zero rows. The target table being
            // visible is what separates them: riptide's own provisioning grants the writer INSERT
            // on the target and (since #470) SELECT on the view, so a visible target beside an
            // invisible view is a grants gap on a deployment provisioned before that.
            return new Result(rollup, Status.UNVERIFIABLE,
                    "materialized view " + mv + " is not visible to the connecting user — re-run "
                            + "'riptide onboard', or GRANT SELECT ON " + mv + " to its role");
        }

        if (!normalise(intendedSelect).equals(normalise(liveSelect))) {
            return new Result(rollup, Status.DRIFTED,
                    "materialized view " + mv + " selects a different shape than this version emits");
        }
        return new Result(rollup, Status.MATCHES, "");
    }

    /**
     * Reduce a SELECT to what the server's own re-serialisation preserves.
     *
     * <p>ClickHouse does not store the SQL it was given. {@code system.tables.as_select} comes back
     * on a single line, with the backticks riptide writes around the database name stripped, so a
     * literal comparison against the emitted DDL fails on every rollup and every deployment.
     * Stripping backticks and collapsing runs of whitespace makes the two agree — verified against
     * riptide's real emitted DDL on server versions 25.3 and 26.7, for all four rollups.</p>
     *
     * <p><b>The one blind spot.</b> Collapsing whitespace collapses it inside string literals too,
     * so two SELECTs differing only by whitespace within a literal would compare equal. Unreachable
     * today — the only literals in the rollup SELECTs are {@code ''}, {@code 'INGRESS'} and
     * {@code 'EGRESS'} — and a test holds that property so it cannot open silently.</p>
     */
    static String normalise(final String select) {
        return WHITESPACE_RUN.matcher(select.replace("`", "")).replaceAll(" ").trim();
    }
}
