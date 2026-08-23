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
        /**
         * The target table itself could not be seen, so a query routed there would fail outright —
         * with {@code UNKNOWN_TABLE} if it is absent, {@code ACCESS_DENIED} if it is merely
         * ungranted. Distinct from {@link #UNVERIFIABLE}: this rollup is not unknown, it is
         * unusable.
         */
        UNREACHABLE,
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

        /**
         * Whether the query path must avoid this rollup.
         *
         * <p>Drift and unreachability both qualify, for different reasons: a drifted rollup would
         * answer from a shape this version did not write, and an unreachable one would not answer
         * at all. {@link Status#UNVERIFIABLE} does not — a rollup that could not be checked is not
         * thereby known to be wrong, and declining it would degrade every query on a deployment
         * whose only fault is a missing grant.</p>
         */
        public boolean declineForQueries() {
            return this.status == Status.DRIFTED || this.status == Status.UNREACHABLE;
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
     * @param liveSortKeys   rollup target table to its {@code system.tables.sorting_key}. A rollup
     *                       absent from this map has an unread key, which is reported rather than
     *                       assumed correct — see {@link #compareOne}.
     */
    public static List<Result> compare(final String database,
            final Map<String, String> liveSelects,
            final Map<String, Map<String, String>> liveColumns,
            final Map<String, String> liveSortKeys) {
        final Map<String, String> intendedSelects = FlowsSchema.rollupSelects(database);
        final Map<String, String> intendedSortKeys = FlowsSchema.rollupSortKeys();
        final List<Result> results = new ArrayList<>();
        for (final var intended : FlowsSchema.rollupColumns().entrySet()) {
            results.add(compareOne(intended.getKey(), intended.getValue(),
                    intendedSelects.get(intended.getKey()), liveSelects, liveColumns,
                    intendedSortKeys.get(intended.getKey()), liveSortKeys));
        }
        return List.copyOf(results);
    }

    private static Result compareOne(final String rollup,
            final Map<String, String> intendedColumns,
            final String intendedSelect,
            final Map<String, String> liveSelects,
            final Map<String, Map<String, String>> liveColumns,
            final String intendedSortKey,
            final Map<String, String> liveSortKeys) {
        final Map<String, String> live = liveColumns.get(rollup);
        if (live == null) {
            // Not merely unknown: a query routed here would fail with UNKNOWN_TABLE or
            // ACCESS_DENIED, so the query path must avoid it and answer from raw flows instead.
            return new Result(rollup, Status.UNREACHABLE,
                    "target table is not visible to the connecting user — it is absent, or the user "
                            + "holds no grant on it. Queries will use raw flows instead");
        }

        // Column drift is decided first and independently of whether the view can be read: it is
        // verified either way, and reporting "cannot verify" while holding proof of drift would
        // bury the finding.
        final Set<String> missing = new TreeSet<>(intendedColumns.keySet());
        missing.removeAll(live.keySet());
        final Set<String> unexpected = new TreeSet<>(live.keySet());
        unexpected.removeAll(intendedColumns.keySet());
        // Types, not just names. A dimension whose width changed (srcAs UInt32 -> UInt64) or a
        // measure narrowed below UInt64 keeps its name and would otherwise pass clean, while
        // silently truncating or wrapping on a busy exporter.
        final Set<String> retyped = new TreeSet<>();
        intendedColumns.forEach((column, type) -> {
            final String liveType = live.get(column);
            if (liveType != null && !liveType.equals(type)) {
                retyped.add(column + " is " + liveType + ", expected " + type);
            }
        });
        if (!missing.isEmpty() || !unexpected.isEmpty() || !retyped.isEmpty()) {
            // Reported separately because they mean different things: missing is this version
            // expecting a column the table never got, unexpected is the table carrying one this
            // version stopped writing, retyped is the same column holding a different type.
            final StringBuilder detail = new StringBuilder("target table columns differ —");
            if (!missing.isEmpty()) {
                detail.append(" missing ").append(missing);
            }
            if (!unexpected.isEmpty()) {
                detail.append(" unexpected ").append(unexpected);
            }
            if (!retyped.isEmpty()) {
                detail.append(" wrong type ").append(retyped);
            }
            return new Result(rollup, Status.DRIFTED, detail.toString());
        }

        // The sorting key, and NOT because it is tidy to check everything. A target can carry every
        // intended column, at the right type, with a view selecting exactly this version's SELECT,
        // and still have the rate OUTSIDE its sorting key — a state ClickHouse cannot repair
        // (Code 36) and an operator can reach by hand. There the rate is a plain numeric column of a
        // SummingMergeTree, so the engine SUMS IT across merges: sum(bytes * samplingInterval)
        // inflated by an arbitrary factor, and samplingInterval > 0 no longer meaning what the
        // schema says. Columns and SELECT both compare clean, so without this the verdict is
        // MATCHES and the rollup answers every long-range query with a wrong number.
        //
        // Checked from the live catalog rather than remembered by whichever code path did the
        // refusing: a validate-mode collector issues no DDL and computes no repair plan at all, and
        // that is precisely the deployment shape that cannot fix itself.
        final String liveSortKey = liveSortKeys.get(rollup);
        if (liveSortKey == null) {
            return new Result(rollup, Status.UNVERIFIABLE,
                    "sorting key of " + rollup + " could not be read — the query path keeps using it,"
                            + " but a key this version cannot see is a key it cannot vouch for");
        }
        if (!normaliseKey(intendedSortKey).equals(normaliseKey(liveSortKey))) {
            return new Result(rollup, Status.DRIFTED,
                    "target table sorting key is (" + liveSortKey + "), this version writes ("
                            + intendedSortKey + "). A dimension outside the sorting key is summed by"
                            + " SummingMergeTree rather than grouped by");
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

    /** A sorting key differing only in spacing or backticks is the same key. */
    static String normaliseKey(final String key) {
        return WHITESPACE_RUN.matcher(key.replace("`", "")).replaceAll(" ").trim();
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
    public static String normalise(final String select) {
        return WHITESPACE_RUN.matcher(select.replace("`", "")).replaceAll(" ").trim();
    }
}
