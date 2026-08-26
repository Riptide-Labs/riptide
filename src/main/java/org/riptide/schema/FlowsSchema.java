/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.schema;

import org.intellij.lang.annotations.Language;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The one definition of riptide's ClickHouse flow schema — the {@code flows} table and the
 * {@code samples} view — as database-qualified, idempotent DDL. Pure string builders (no I/O), so
 * both consumers share one source and cannot drift:
 *
 * <ul>
 *   <li>the collector's manage-schema path ({@code ClickhouseRepository}) creates the database,
 *       table, and view;</li>
 *   <li>{@code onboard} ({@code ProvisioningDdl.ensureShared}) creates the database and table so a
 *       provisioned deployment can be onboarded before any {@code GRANT}/{@code ALTER} that needs
 *       the table to exist.</li>
 * </ul>
 *
 * <p>Every statement names {@code `<db>`.flows} / {@code `<db>`.samples} explicitly rather than
 * relying on a client's default-database pinning, so the same DDL runs correctly on the collector's
 * pinned client and on an unpinned admin client. The database name is charset-checked and
 * backtick-quoted here — the collector's {@code riptide.clickhouse.database} property binds without
 * validation, so the quoting site is the enforcement point.
 *
 * <p>The {@code samples} view carries no data and is created with {@code CREATE OR REPLACE}, so it
 * always tracks the running version. It is used only by the collector's manage path — {@code
 * onboard} does not create it (in provisioned mode the reader role is not granted {@code SELECT} on
 * it, so it would be inert).
 *
 * <p>Alongside the raw table sit the <strong>1-minute rollups</strong>: {@code SummingMergeTree}
 * targets fed by materialized views on {@code flows}. They are emitted as ordinary tables plus
 * {@code …_mv} views so the provisioning path can create, grant, and row-policy them exactly like
 * the raw table. A materialized view does not backfill, so a rollup covers traffic from its
 * creation onward.
 */
public final class FlowsSchema {

    /** The collector's manage-mode retention; also the {@code onboard --ttl-days} default. */
    public static final int DEFAULT_TTL_DAYS = 30;

    /**
     * Rollup retention, deliberately far longer than {@link #DEFAULT_TTL_DAYS}: the rollups exist so
     * long-range queries survive the raw table's expiry, which only works if they outlive it.
     */
    public static final int DEFAULT_ROLLUP_TTL_DAYS = 365;

    /** Same charset as the provisioning boundary ({@code TenantSpec}): no quotes, backticks, spaces. */
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_-]+");

    private FlowsSchema() {
    }

    /** {@code CREATE DATABASE IF NOT EXISTS `<db>`} — safe on an unpinned connection. */
    public static String createDatabase(final String database) {
        return "CREATE DATABASE IF NOT EXISTS " + ident(database);
    }

    /** As {@link #createFlowsTable(String, int)} with the collector's default retention. */
    public static String createFlowsTable(final String database) {
        return createFlowsTable(database, DEFAULT_TTL_DAYS);
    }

    /** {@code CREATE TABLE IF NOT EXISTS `<db>`.flows (…)} — a pre-existing table is left intact. */
    public static String createFlowsTable(final String database, final int ttlDays) {
        return FLOWS_TABLE
                .replace(FLOWS_TOKEN, qualifiedFlows(database))
                .replace(TTL_DAYS_TOKEN, Integer.toString(ttlDays));
    }

    /** {@code CREATE OR REPLACE VIEW `<db>`.samples AS … FROM `<db>`.flows} — collector-only. */
    public static String createSamplesView(final String database) {
        return SAMPLES_VIEW
                .replace(SAMPLES_TOKEN, ident(database) + ".samples")
                .replace(FLOWS_TOKEN, qualifiedFlows(database));
    }

    /** The qualified {@code `<db>`.flows} name — the one home for its construction. */
    public static String qualifiedFlows(final String database) {
        return ident(database) + ".flows";
    }

    /** The qualified {@code `<db>`.<rollup>} target-table name. */
    public static String qualifiedRollup(final String database, final String table) {
        return ident(database) + "." + table;
    }

    /** The qualified {@code `<db>`.<rollup>_mv} materialized-view name. */
    public static String qualifiedRollupView(final String database, final String table) {
        return qualifiedRollup(database, table) + "_mv";
    }

    /**
     * The rollup target-table names. Callers that must touch every rollup — {@code GRANT}, row
     * policies, existence checks — iterate this rather than hard-coding a list that would silently
     * miss a rollup added here later.
     */
    public static List<String> rollupTableNames() {
        return ROLLUPS.stream().map(Rollup::table).toList();
    }


    /** As {@link #createRollupTables(String, int)} with the default rollup retention. */
    public static List<String> createRollupTables(final String database) {
        return createRollupTables(database, DEFAULT_ROLLUP_TTL_DAYS);
    }

    /** {@code CREATE TABLE IF NOT EXISTS} for every rollup target — pre-existing tables are left intact. */
    public static List<String> createRollupTables(final String database, final int ttlDays) {
        return ROLLUPS.stream().map(rollup -> rollupTable(database, rollup, ttlDays)).toList();
    }

    /**
     * As {@link #createRollupTables}, keyed by rollup so a caller can attribute a failure to one
     * rollup and carry on with the rest instead of failing the whole start.
     */
    public static Map<String, String> createRollupTablesByRollup(final String database) {
        final Map<String, String> tables = new LinkedHashMap<>();
        for (final Rollup rollup : ROLLUPS) {
            tables.put(rollup.table(), rollupTable(database, rollup, DEFAULT_ROLLUP_TTL_DAYS));
        }
        return Collections.unmodifiableMap(tables);
    }

    /**
     * {@code CREATE MATERIALIZED VIEW IF NOT EXISTS … TO <target>} for every rollup. Must be emitted
     * <em>after</em> {@link #createRollupTables} — a view whose {@code TO} target does not yet exist
     * fails to create.
     *
     * <p><b>Not for production callers.</b> Both real paths use {@link #createRollupViewsByRollup},
     * which lets them skip a rollup whose target was refused; emitting all four unconditionally is
     * how riptide once built the very view a refusal existed to prevent. This overload remains for
     * tests that want the whole list.</p>
     */
    public static List<String> createRollupViews(final String database) {
        return ROLLUPS.stream().map(rollup -> rollupView(database, rollup)).toList();
    }

    /**
     * As {@link #createRollupViews}, keyed by rollup so a caller can attribute a failure.
     *
     * <p>Worth attributing because the statement can fail for a reason that is not fatal: the SELECT
     * names every dimension this version intends, and {@code CREATE … IF NOT EXISTS} validates it
     * against the target even when the view already exists. A target that has not been repaired —
     * refused, or deferred — therefore rejects the create, and the caller needs to know which
     * rollup to report rather than losing ingestion over it.</p>
     */
    public static Map<String, String> createRollupViewsByRollup(final String database) {
        final Map<String, String> views = new LinkedHashMap<>();
        for (final Rollup rollup : ROLLUPS) {
            views.put(rollup.table(), rollupView(database, rollup));
        }
        return Collections.unmodifiableMap(views);
    }

    /**
     * A rollup target table: every dimension in the sort key, every measure a {@code UInt64} the
     * {@code SummingMergeTree} engine collapses on merge.
     */
    private static String rollupTable(final String database, final Rollup rollup, final int ttlDays) {
        final List<Dimension> columns = allDimensions(rollup);
        final StringBuilder ddl = new StringBuilder("CREATE TABLE IF NOT EXISTS ")
                .append(qualifiedRollup(database, rollup.table()))
                .append(" (\n");
        for (final Dimension dimension : columns) {
            ddl.append("    ").append(dimension.column()).append(' ').append(dimension.type()).append(",\n");
        }
        ddl.append(MEASURES.stream()
                .map(measure -> "    " + measure.column() + " UInt64")
                .collect(Collectors.joining(",\n")));
        // Sorting by every dimension is what makes SummingMergeTree collapse correctly: rows agree
        // on the full key or they are distinct facts.
        //
        // PRIMARY KEY is declared even though it is identical to ORDER BY, and the duplication is
        // deliberate — do not "simplify" it away. Left undeclared, ClickHouse derives the primary
        // key from the sorting key, and a later ALTER ... MODIFY ORDER BY appends to the SORTING
        // key only. An upgraded install would then hold an N-column primary key under an
        // N+1-column sorting key while a fresh install derived N+1 for both, and nothing in a
        // column-name or type comparison can see the difference.
        //
        // The two lists are equal today and are named separately so they are free to diverge. When
        // a dimension is appended to a rollup, ORDER BY grows and the PRIMARY KEY MUST STAY AS IT
        // IS: replace the assignment below with the literal list as of that moment. Growing both
        // recreates the very divergence this prevents, because ALTER ... MODIFY ORDER BY cannot
        // grow an existing table's primary key to match (there is no MODIFY PRIMARY KEY at all).
        // The tests assert prefix rather than equality precisely so that freeze is allowed, and
        // they pin each rollup's primary key as a literal so the append cannot pass unnoticed
        // (#470, #571).
        final String sortKeyColumns = columns.stream().map(Dimension::column).collect(Collectors.joining(", "));
        final String primaryKeyColumns = rollup.frozenPrimaryKey();
        ddl.append("\n) ENGINE = SummingMergeTree()\nPRIMARY KEY (")
                .append(primaryKeyColumns)
                .append(")\nORDER BY (")
                .append(sortKeyColumns)
                .append(")\nPARTITION BY toYYYYMM(timestamp)\n")
                .append("TTL timestamp + INTERVAL ").append(ttlDays).append(" DAY\n")
                .append("SETTINGS index_granularity = 8192");
        return ddl.toString();
    }

    /**
     * The in-place repair for every rollup target: one {@code ALTER} per rollup that adds any
     * missing dimension and sets the sorting key to this version's (#470).
     *
     * <p><b>Emitted only for the rollups {@link #planRollupRepair} selects</b>, never unconditionally
     * — both callers gate it, and classifying first is the point. The statement is idempotent where
     * it applies ({@code ADD COLUMN IF NOT EXISTS} no-ops, and {@code MODIFY ORDER BY} to the key a
     * table already has changes nothing), but idempotent is not the same as safe: on a table whose
     * key this version would <em>shrink</em> the server accepts it and silently changes the grain
     * (#571 froze the primary key, so the prefix rule no longer catches it), and on a table already
     * carrying the column outside its sorting key it fails with Code 36 on every start forever. The
     * planner exists for both.</p>
     *
     * <p><b>One statement per rollup, not one per column.</b> ClickHouse rejects a sorting key that
     * gains a column the same {@code ALTER} did not add — <em>"Existing column X is used in the
     * expression that was added to the sorting key. You can add expressions that use only the newly
     * added columns."</em> Adding the column in a previous statement therefore closes the only
     * in-place route, and the two must travel together.</p>
     *
     * <p><b>No {@code DEFAULT} clause, deliberately.</b> ClickHouse also rejects <em>"Newly added
     * column X has a default expression, so adding expressions that use it to the sorting key is
     * forbidden."</em> The implicit type default still applies, so rows aggregated before the append
     * read {@code ''} or {@code 0} — which is the boundary an appended dimension depends on to mark
     * its own history, not an accident. See {@link Dimension#absent()}.</p>
     *
     * <p>The primary key is untouched and stays frozen at whatever the table was created with, which
     * is what keeps a fresh install and an upgraded one agreeing (#571).</p>
     */
    public static Map<String, String> alterRollupTargets(final String database) {
        final Map<String, String> alters = new LinkedHashMap<>();
        for (final Rollup rollup : ROLLUPS) {
            alters.put(rollup.table(), alterRollupTarget(database, rollup));
        }
        return Collections.unmodifiableMap(alters);
    }

    private static String alterRollupTarget(final String database, final Rollup rollup) {
        final List<Dimension> columns = allDimensions(rollup);
        final StringBuilder ddl = new StringBuilder("ALTER TABLE ")
                .append(qualifiedRollup(database, rollup.table()));
        for (int i = 0; i < columns.size(); i++) {
            final Dimension dimension = columns.get(i);
            ddl.append("\n    ADD COLUMN IF NOT EXISTS ")
                    .append(dimension.column()).append(' ').append(dimension.type());
            // POSITIONED, not appended. Without AFTER, ClickHouse puts a new column last — past the
            // measures — so an upgraded target ends up with a different physical column order than a
            // fresh one. Riptide itself does not care (a materialized view with TO matches by name),
            // but INSERT ... SELECT without a column list is POSITIONAL, and that is exactly the
            // backfill the ClickHouse guide tells operators to write. On an upgraded target it would
            // land the rate in `bytes` and shift every measure by one, while samplingInterval takes
            // its type default — the reserved sentinel — so `WHERE samplingInterval > 0` then hides
            // the corruption it just caused. The flows table already guarantees this invariant
            // (addAdditiveColumns); rollups must too. Verified on 26.7: AFTER is accepted in the
            // same statement as MODIFY ORDER BY, and re-running it changes nothing.
            if (i > 0) {
                ddl.append(" AFTER ").append(columns.get(i - 1).column());
            }
            ddl.append(',');
        }
        ddl.append("\n    MODIFY ORDER BY (").append(sortKey(rollup)).append(')');
        return ddl.toString();
    }

    /**
     * Which rollup views this version should re-point, decided from the views' own live SELECT.
     *
     * <p>Shared by the collector and {@code onboard} for the same reason {@link #planRollupRepair}
     * is: they are the only two callers, and they were not previously obliged to agree. They now do
     * by construction — {@code onboard} used to re-point every present view unconditionally, which
     * emitted four {@code ALTER}s against an already-correct database (the spec requires a re-run to
     * issue none) and carried no downgrade guard at all.</p>
     *
     * <p><b>Column set, not text.</b> A view whose columns match but whose expression differs is a
     * corrected aggregate, and repairing that is out of scope: it would readmit rows computed the
     * old way with nothing distinguishing them.</p>
     *
     * <p><b>Growth only.</b> A view selecting columns this version does not know is a downgrade.
     * Re-pointing it would not fail — {@code MODIFY QUERY} does not validate against its target —
     * it would drop that column on every insert, and for a sort-key dimension the type default is a
     * reserved sentinel. Declining is recoverable; writing over live rows is not.</p>
     *
     * @param liveSelects  {@code <rollup>_mv} to its stored {@code as_select}; a view absent from
     *                     the map is not re-pointed, since there is nothing to re-point
     * @param refused      rollups whose target is not being repaired, excluded because a view
     *                     naming a column its target lacks silently drops it on every insert
     * @return the rollups to re-point, and the downgrades refused with a reason to report
     */
    public static RepairPlan planViewRepair(final String database,
            final Map<String, String> liveSelects, final Set<String> refused) {
        final List<String> stale = new ArrayList<>();
        final Map<String, String> declined = new LinkedHashMap<>();
        rollupSelects(database).forEach((rollup, intended) -> {
            final String current = liveSelects.get(rollup + "_mv");
            if (current == null || refused.contains(rollup)) {
                return;
            }
            final Set<String> now = selectOutputColumns(current);
            final Set<String> wanted = selectOutputColumns(intended);
            if (now.equals(wanted)) {
                return;
            }
            if (!wanted.containsAll(now)) {
                final Set<String> extra = new LinkedHashSet<>(now);
                extra.removeAll(wanted);
                declined.put(rollup, "its materialized view selects " + extra + ", which this version"
                        + " does not know — that is a downgrade, and taking the columns away would"
                        + " write this version's defaults over rows that mean something else");
                return;
            }
            stale.add(rollup);
        });
        return new RepairPlan(List.copyOf(stale), Collections.unmodifiableMap(declined));
    }

    /**
     * The aliases a SELECT emits, which is what its target table has to carry.
     *
     * <p>Bounded to the projection: {@code AS} also introduces the source alias in
     * {@code FROM … AS f}, and would introduce a cast or subquery alias in any future expression.
     * Those are not output columns, and the downgrade branch computes a set difference over this —
     * so one stray name there would read as a column a newer version knows.</p>
     */
    private static Set<String> selectOutputColumns(final String select) {
        final String normalised = RollupShapeCheck.normalise(select);
        final int from = normalised.lastIndexOf(" FROM ");
        final var matcher = SELECT_ALIAS.matcher(from < 0 ? normalised : normalised.substring(0, from));
        final Set<String> names = new LinkedHashSet<>();
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    private static final Pattern SELECT_ALIAS = Pattern.compile("\\bAS\\s+([A-Za-z_][A-Za-z0-9_]*)");

    /**
     * Which rollups need repairing, and which must be refused, decided from their live shape (#470).
     *
     * <p>Shared by the collector and by {@code onboard} so the two cannot disagree about what is
     * safe. Pure — the caller supplies the live state and decides what to do with the verdict —
     * which is what lets the interesting cases be unit-tested without a server.</p>
     *
     * @param liveSortKeys        rollup target to its {@code system.tables.sorting_key}; a rollup
     *                            absent from the map is not visible and is left alone
     * @param liveDimensionNames  rollup target to the column names it currently has
     */
    public static RepairPlan planRollupRepair(final Map<String, String> liveSortKeys,
            final Map<String, Set<String>> liveDimensionNames) {
        final List<String> repair = new ArrayList<>();
        final Map<String, String> refused = new LinkedHashMap<>();

        for (final Rollup rollup : ROLLUPS) {
            final String table = rollup.table();
            final String live = liveSortKeys.get(table);
            if (live == null) {
                continue;
            }
            final List<String> liveKey = splitKey(live);
            final List<String> wantedKey = allDimensions(rollup).stream().map(Dimension::column).toList();

            if (liveKey.equals(wantedKey)
                    && liveDimensionNames.getOrDefault(table, Set.of()).containsAll(wantedKey)) {
                continue;
            }
            if (!isPrefix(liveKey, wantedKey)) {
                // Compared as column lists, never as strings: "tenant, srcAsn" starts with
                // "tenant, srcAs" as text, so a renamed trailing dimension would be waved through
                // as an append and the ALTER would then fail on the server at startup.
                //
                // A sorting key may only grow, and only by columns the same ALTER adds. Before #571
                // the primary key was derived, so ClickHouse's prefix rule rejected a shrink
                // outright; freezing the primary key made a shrink legal on exactly the upgraded
                // tables this guard exists for, so it has to be refused here rather than left to
                // the server.
                refused.put(table, "sorting key (" + live + ") cannot become (" + String.join(", ", wantedKey)
                        + ") in place: that is not an append, so the grain would change and existing"
                        + " rows would not be re-aggregated");
                continue;
            }

            // A column that already exists but sits OUTSIDE the sorting key cannot be added to it.
            // ClickHouse rejects the pair as Code 36 ("Existing column X is used in the expression
            // that was added to the sorting key") because MODIFY ORDER BY may only name columns the
            // same ALTER adds — verified on 26.7. Riptide never produces this state, but an operator
            // who hand-adds the column after reading that riptide appends it does, and planning the
            // repair anyway would fail identically on every start and every onboard run forever with
            // nothing saying why. Refusing names the remedy instead.
            final Set<String> liveColumns = liveDimensionNames.getOrDefault(table, Set.of());
            final List<String> outsideKey = wantedKey.stream()
                    .filter(column -> !liveKey.contains(column))
                    .filter(liveColumns::contains)
                    .toList();
            if (!outsideKey.isEmpty()) {
                refused.put(table, outsideKey + " already exist as columns but are not in the sorting"
                        + " key (" + live + "), and ClickHouse cannot add an existing column to a"
                        + " sorting key — drop " + outsideKey + " from " + table + " and restart, or"
                        + " drop and re-create the rollup to have it rebuilt");
                continue;
            }
            repair.add(table);
        }
        return new RepairPlan(List.copyOf(repair), Collections.unmodifiableMap(refused));
    }

    /**
     * The rollups to repair, and the ones refused with the reason to report.
     *
     * <p>Only dimensions are considered. A rollup missing a <em>measure</em> is not repairable by
     * this path — {@code ALTER … ADD COLUMN} could add it, but a measure reading {@code 0} for
     * historical rows makes a {@code SUM} spanning the upgrade quietly too small, which is why
     * measures are out of scope. Including them here would plan a repair that never converges and
     * log an identical-keys line on every boot forever.</p>
     */
    public record RepairPlan(List<String> repair, Map<String, String> refused) {
    }

    private static List<String> splitKey(final String key) {
        // Normalised the same way RollupShapeCheck compares keys, so the planner and the check
        // cannot disagree about whether a live key equals this version's. They read the same
        // catalog string; two different notions of equality would let one call a table current
        // while the other calls it drifted — declining every rollup forever while no code path
        // ever attempts the repair the log demands.
        final String normalised = RollupShapeCheck.normaliseKey(key);
        return normalised.isBlank() ? List.of() : List.of(normalised.split(",\\s*"));
    }

    private static boolean isPrefix(final List<String> shorter, final List<String> longer) {
        return shorter.size() <= longer.size() && longer.subList(0, shorter.size()).equals(shorter);
    }

    /**
     * The sorting key this version intends for each rollup, keyed by target table name.
     *
     * <p>Exposed so a caller holding the live {@code system.tables.sorting_key} can refuse to apply
     * a repair that would <em>shrink</em> it. #571 froze the primary key, which made a shrink legal
     * where ClickHouse's prefix rule used to reject it, so a reverted dimension would otherwise
     * change a rollup's grain silently.</p>
     */
    public static Map<String, String> rollupSortKeys() {
        final Map<String, String> keys = new LinkedHashMap<>();
        for (final Rollup rollup : ROLLUPS) {
            keys.put(rollup.table(), sortKey(rollup));
        }
        return Collections.unmodifiableMap(keys);
    }

    private static String sortKey(final Rollup rollup) {
        return allDimensions(rollup).stream().map(Dimension::column).collect(Collectors.joining(", "));
    }

    /**
     * {@code ALTER TABLE <view> MODIFY QUERY} for every rollup's materialized view.
     *
     * <p>Reuses the very SELECT {@link #createRollupViews} emits, so a repaired view and a freshly
     * created one cannot differ — which is also what {@code detect-rollup-shape-drift} compares
     * against, so a repair verifies clean on the same start rather than warning once per upgrade.</p>
     *
     * <p>{@code MODIFY QUERY} swaps the SELECT in place and does not interrupt aggregation. Measured
     * at zero loss under continuous insert, against 0.44% for the {@code DROP}/{@code CREATE} path
     * that a materialized view would otherwise need — see {@code design.md}.</p>
     *
     * <p><b>Must be emitted after {@link #alterRollupTargets}, and the server will not tell you if
     * it is not.</b> Unlike {@code CREATE MATERIALIZED VIEW}, which rejects a SELECT naming a column
     * its target lacks ({@code THERE_IS_NO_COLUMN}), {@code MODIFY QUERY} accepts it — verified on
     * 26.7 — and then silently discards that column on every insert. Out of order, this does not
     * fail: it produces a view that aggregates by a dimension the target throws away, which reads as
     * a working rollup answering with the wrong grain.</p>
     */
    public static Map<String, String> modifyRollupViews(final String database) {
        final Map<String, String> modifies = new LinkedHashMap<>();
        for (final Rollup rollup : ROLLUPS) {
            modifies.put(rollup.table(), "ALTER TABLE " + qualifiedRollupView(database, rollup.table())
                    + " MODIFY QUERY\n" + rollupSelect(database, rollup));
        }
        return Collections.unmodifiableMap(modifies);
    }

    /** The materialized view feeding one rollup target from {@code flows}. */
    private static String rollupView(final String database, final Rollup rollup) {
        return "CREATE MATERIALIZED VIEW IF NOT EXISTS "
                + qualifiedRollupView(database, rollup.table())
                + " TO " + qualifiedRollup(database, rollup.table())
                + " AS\n" + rollupSelect(database, rollup);
    }

    /**
     * The SELECT each rollup's materialized view is created with, keyed by target table name.
     *
     * <p>Exposed so drift detection compares against the very text {@link #createRollupViews} emits
     * rather than re-deriving it. A second derivation would be free to drift from the first, and
     * the failure mode is the worst kind: a comparator that reports every deployment as stale, or
     * none.</p>
     *
     * <p><b>Comparing this against a live server needs normalisation.</b> ClickHouse re-serialises
     * what it stores in {@code system.tables.as_select}: the SELECT comes back on one line and
     * without the backticks written around the database name. Stripping backticks and collapsing
     * runs of whitespace makes the two agree, verified against the real emitted DDL on server
     * versions 25.3 and 26.7. Note that collapsing whitespace also collapses it inside string
     * literals, so no rollup expression may contain a literal with internal whitespace — today the
     * only literals are {@code ''}, {@code 'INGRESS'} and {@code 'EGRESS'}, and a test holds that.</p>
     */
    public static Map<String, String> rollupSelects(final String database) {
        final Map<String, String> selects = new LinkedHashMap<>();
        for (final Rollup rollup : ROLLUPS) {
            selects.put(rollup.table(), rollupSelect(database, rollup));
        }
        return Collections.unmodifiableMap(selects);
    }

    private static String rollupSelect(final String database, final Rollup rollup) {
        final List<Dimension> columns = allDimensions(rollup);
        final StringBuilder select = new StringBuilder("SELECT\n");
        select.append(columns.stream()
                .map(dimension -> "    " + dimension.selectItem())
                .collect(Collectors.joining(",\n")));
        select.append(",\n").append(MEASURES.stream()
                .map(measure -> "    " + measure.expression() + " AS " + measure.column())
                .collect(Collectors.joining(",\n")));
        select.append("\nFROM ").append(qualifiedFlows(database)).append(" AS ").append(SOURCE_ALIAS)
                .append("\nGROUP BY ")
                .append(columns.stream().map(Dimension::column).collect(Collectors.joining(", ")));
        return select.toString();
    }

    /**
     * The columns each rollup target table is intended to carry — every dimension in sort-key order,
     * then every measure — keyed by target table name.
     *
     * <p>Compare this against {@code system.columns}, never against
     * {@code system.tables.create_table_query}. Since the primary key became explicit (#571) the
     * stored DDL text differs permanently between a table created before that change and one
     * created after, because the DDL is {@code CREATE TABLE IF NOT EXISTS} and never rewrites a
     * table that already exists. A text comparison would call every older deployment stale.</p>
     */
    public static Map<String, Map<String, String>> rollupColumns() {
        final Map<String, Map<String, String>> columns = new LinkedHashMap<>();
        for (final Rollup rollup : ROLLUPS) {
            final Map<String, String> types = new LinkedHashMap<>();
            allDimensions(rollup).forEach(dimension -> types.put(dimension.column(), dimension.type()));
            // Every measure is a UInt64 the SummingMergeTree collapses on merge; the width is part
            // of the contract, not an implementation detail — a narrower one would overflow on a
            // busy exporter and wrap silently.
            MEASURES.forEach(measure -> types.put(measure.column(), "UInt64"));
            columns.put(rollup.table(), Collections.unmodifiableMap(types));
        }
        return Collections.unmodifiableMap(columns);
    }

    /** The shared preamble followed by the rollup's own dimensions — the full sort key, in order. */
    private static List<Dimension> allDimensions(final Rollup rollup) {
        return Stream.concat(PREAMBLE.stream(), rollup.dimensions().stream()).toList();
    }

    /**
     * Columns added after the 0.4.x schema, upgradeable in place — the one home for both
     * CREATE and ALTER emission. Order matters: it is the trailing column order of
     * {@link #createFlowsTable}, so a fresh and an upgraded table end up identical.
     */
    private static final Map<String, String> ADDITIVE_COLUMNS = new LinkedHashMap<>();

    static {
        ADDITIVE_COLUMNS.put("srcCountry", "LowCardinality(String)");
        ADDITIVE_COLUMNS.put("srcCity", "LowCardinality(String)");
        ADDITIVE_COLUMNS.put("dstCountry", "LowCardinality(String)");
        ADDITIVE_COLUMNS.put("dstCity", "LowCardinality(String)");
        ADDITIVE_COLUMNS.put("exporterName", "LowCardinality(String)");
        ADDITIVE_COLUMNS.put("samplingProvenance", "LowCardinality(String)");
    }

    /** The additive column names, for callers distinguishing in-place-upgradeable columns. */
    public static Set<String> additiveColumnNames() {
        return Collections.unmodifiableSet(ADDITIVE_COLUMNS.keySet());
    }

    /**
     * Idempotent additive upgrade: {@code ALTER TABLE … ADD COLUMN IF NOT EXISTS} for each
     * additive column. Safe on any table — a fresh one (columns exist, no-op) or a pre-upgrade
     * one (columns appended in definition order, matching {@link #createFlowsTable}).
     */
    public static List<String> addAdditiveColumns(final String database) {
        final String flows = qualifiedFlows(database);
        return ADDITIVE_COLUMNS.entrySet().stream()
                .map(column -> "ALTER TABLE " + flows + " ADD COLUMN IF NOT EXISTS "
                        + column.getKey() + " " + column.getValue())
                .toList();
    }

    /**
     * Charset-check and backtick-quote an identifier. Enforced here (not just documented) because
     * the collector's database name arrives from unvalidated configuration; a bad value fails with
     * a clear message instead of producing malformed DDL.
     */
    private static String ident(final String name) {
        if (name == null || !SAFE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "invalid ClickHouse database name '" + name + "' — riptide.clickhouse.database"
                            + " (or onboard --database) must match [A-Za-z0-9_-]+"
                            + " (letters, digits, underscore, hyphen)");
        }
        return "`" + name + "`";
    }

    /** The {@code flows} alias every rollup view's expressions qualify against. */
    private static final String SOURCE_ALIAS = "f";

    /**
     * {@code application} is {@code Nullable(String)} on the raw table but a sort key on every
     * rollup that carries it, so the null is folded to {@code ''} on the way in — a nullable sort
     * key would make the {@code SummingMergeTree} collapse depend on null comparison.
     */
    private static final Dimension APPLICATION =
            new Dimension("application", "LowCardinality(String)", "ifNull(f.application, '')");

    /**
     * The sampling rate a flow's counters are scaled by, carried so
     * {@code SUM(bytes * samplingInterval)} means the same thing against a rollup as against the raw
     * table — which it could not before, leaving sampling-corrected volume unanswerable beyond the
     * raw table's retention (#467, #470).
     *
     * <p>A dimension rather than a pre-scaled measure. A measure reading {@code 0} for rows
     * aggregated before the append would make a {@code SUM} spanning the upgrade quietly too small,
     * with nothing marking where. A rate of {@code 0} is not a value any exporter, fallback or
     * assumption can produce — {@code usable()} admits only finite values {@code >= 1.0} and the
     * persisted default is {@code 1.0} — so the type default marks pre-append rows unambiguously and
     * the boundary is the predicate {@code samplingInterval > 0}.</p>
     *
     * <p>Read straight through, with no {@code ifNull} folding: the source column is not nullable,
     * and a fallback literal would have to avoid emitting the type default anyway.</p>
     *
     * <p><b>This cannot be checked from the schema, so it names where it is enforced instead.</b>
     * The property rests on {@code usable()} in the four builders and on {@code ClickhouseFlow}'s
     * {@code 1.0} default. {@code SamplingIntervalBoundaryTest} is what pins it: it drives every
     * builder's guard reflectively and asserts each rejects {@code 0.0}, {@code -1.0}, {@code 0.5},
     * {@code NaN} and {@code POSITIVE_INFINITY}. {@code SamplingIntervalResolutionTest} pins the
     * resolution path that feeds it.</p>
     *
     * <p>Appended last, which is the only position {@code ALTER … MODIFY ORDER BY} permits.</p>
     */
    private static final Dimension SAMPLING_INTERVAL = Dimension.appended("samplingInterval", "Float64");

    /**
     * The protocol a flow arrived over, carried so sampling-corrected volume is answerable on a
     * deployment that receives sFlow (#583).
     *
     * <p>sFlow scales its counters at ingest and still reports its rate, so multiplying an sFlow row
     * by {@link #SAMPLING_INTERVAL} double-counts it while multiplying a NetFlow or IPFIX row is what
     * corrects it. Nothing but the protocol separates the two cases, so a rollup without this column
     * cannot express the correction at all — it can only produce a total that is wrong one way or the
     * other. The corrected expression is
     * {@code sum(bytes * if(flowProtocol = 'SFLOW', 1, samplingInterval))}, and it works only because
     * both factors are sort-key dimensions: each group then carries its own protocol and its own
     * rate, and the multiply applies each group's factor to its own total.</p>
     *
     * <p><b>Deliberately not the raw table's {@code Enum8}.</b> Verified against ClickHouse 26.7: a
     * row aggregated before an appended {@code Enum8('NetflowV5' = 1, …, 'SFLOW' = 4)} reads back as
     * {@code NetflowV5} — a valid protocol, indistinguishable from a real one. {@code != 'SFLOW'}
     * would then admit every pre-append row, and the sFlow traffic inside them would be inflated by
     * exactly the defect this column exists to fix, silently and with nothing in the data marking it.
     * {@code LowCardinality(String)} reserves {@code ''} instead, which {@code toString()} of a
     * four-member enum can never produce — an invariant {@code FlowsSchemaTest} pins against the
     * shipped {@code flows} DDL rather than leaving to this sentence. It is also how
     * {@code exporterName}, {@code srcCountry} and {@code dstCountry} are already typed here.</p>
     *
     * <p><b>The boundary predicate this column needs is rollup-only.</b> The scaling expression ports
     * to raw {@code flows} unchanged; {@code flowProtocol != ''} does not, and fails there with
     * {@code UNKNOWN_ELEMENT_OF_ENUM} because the source column is an {@code Enum8} with no such
     * member. Raw {@code flows} has no pre-append band to exclude — the column has existed since the
     * table was created. Do not describe the whole query as portable.</p>
     *
     * <p>Row growth is exactly 1.0 where one protocol is received: a single distinct value cannot
     * split a group. Modelled at a 1.001 median and 1.0014 combined across the four rollups on a
     * synthetic mixed-protocol fleet (40 exporters, 3 of them sFlow, 4M records, ClickHouse 26.7),
     * against the 1.0214 median measured for {@link #SAMPLING_INTERVAL}. That is a model, not a fleet
     * capture, and the coarsest rollup ({@code flows_by_application_1m}) modelled at 1.5 on a base of
     * ~11.5k rows. Treat the mixed-deployment figure as unmeasured.</p>
     *
     * <p>Appended after the rate, which is the only position {@code ALTER … MODIFY ORDER BY} permits.</p>
     */
    private static final Dimension FLOW_PROTOCOL =
            Dimension.appended("flowProtocol", "LowCardinality(String)", "toString(f.flowProtocol)");

    /**
     * Dimensions every rollup carries, ahead of its own. The tenant/organisation prefix mirrors the
     * raw table's sort key so the same row policies apply, and {@code timestamp} keeps the raw
     * table's column name so a time filter ports between raw and rollup unchanged — truncated to
     * the minute, which is what makes the rollup a rollup.
     */
    private static final List<Dimension> PREAMBLE = List.of(
            Dimension.of("tenant", "String"),
            Dimension.of("organisation", "String"),
            new Dimension("timestamp", "DateTime('UTC')", "toStartOfMinute(f.timestamp)"),
            Dimension.of("zone", "String"));

    /**
     * The measures every rollup carries. Undirected totals sit alongside the ingress/egress split
     * so a query that does not care about direction needs no reassembly, and one that does is not
     * forced to re-derive it from the raw table.
     */
    private static final List<Measure> MEASURES = List.of(
            new Measure("bytes", "sum(f.bytes)"),
            new Measure("packets", "sum(f.packets)"),
            new Measure("flowCount", "count()"),
            new Measure("bytesIn", "sumIf(f.bytes, f.direction = 'INGRESS')"),
            new Measure("bytesOut", "sumIf(f.bytes, f.direction = 'EGRESS')"),
            new Measure("packetsIn", "sumIf(f.packets, f.direction = 'INGRESS')"),
            new Measure("packetsOut", "sumIf(f.packets, f.direction = 'EGRESS')"));

    /**
     * The rollup target-table names. A query router picking a rollup by dimension has to name one,
     * and {@link #rollupTableNames()} cannot say which: these constants are that reference, so a
     * rename here fails compilation at the call site instead of silently producing SQL against a
     * table that no longer exists.
     */
    public static final String ROLLUP_BY_APPLICATION = "flows_by_application_1m";
    public static final String ROLLUP_BY_CONVERSATION = "flows_by_conversation_1m";
    public static final String ROLLUP_BY_EXPORTER_IFACE = "flows_by_exporter_iface_1m";
    public static final String ROLLUP_BY_GEO_ASN = "flows_by_geo_asn_1m";

    /** The 1-minute rollups. Adding one here propagates to creation, grants, and row policies. */
    private static final List<Rollup> ROLLUPS = List.of(
            new Rollup(ROLLUP_BY_APPLICATION,
                    "tenant, organisation, timestamp, zone, application, protocol",
                    List.of(
                            APPLICATION,
                            Dimension.of("protocol", "UInt8"),
                            SAMPLING_INTERVAL,
                            FLOW_PROTOCOL)),
            new Rollup(ROLLUP_BY_CONVERSATION,
                    "tenant, organisation, timestamp, zone, srcAddr, dstAddr, application",
                    List.of(
                            Dimension.of("srcAddr", "IPv6"),
                            Dimension.of("dstAddr", "IPv6"),
                            APPLICATION,
                            SAMPLING_INTERVAL,
                            FLOW_PROTOCOL)),
            new Rollup(ROLLUP_BY_EXPORTER_IFACE,
                    "tenant, organisation, timestamp, zone, exporterAddr, exporterName, inputSnmp, outputSnmp",
                    List.of(
                            Dimension.of("exporterAddr", "String"),
                            Dimension.of("exporterName", "LowCardinality(String)"),
                            Dimension.of("inputSnmp", "UInt32"),
                            Dimension.of("outputSnmp", "UInt32"),
                            SAMPLING_INTERVAL,
                            FLOW_PROTOCOL)),
            new Rollup(ROLLUP_BY_GEO_ASN,
                    "tenant, organisation, timestamp, zone, srcAs, dstAs, srcCountry, dstCountry",
                    List.of(
                            Dimension.of("srcAs", "UInt64"),
                            Dimension.of("dstAs", "UInt64"),
                            Dimension.of("srcCountry", "LowCardinality(String)"),
                            Dimension.of("dstCountry", "LowCardinality(String)"),
                            SAMPLING_INTERVAL,
                            FLOW_PROTOCOL)));

    /**
     * One rollup: its target table name, the dimensions it adds to {@link #PREAMBLE}, and its
     * <strong>frozen primary key</strong>.
     *
     * <p>The primary key is a literal, not derived from {@link #dimensions}, and that is the whole
     * point. {@code ALTER … MODIFY ORDER BY} grows the sorting key and leaves the primary key
     * alone — there is no {@code MODIFY PRIMARY KEY} at all — so an upgraded install keeps the
     * primary key its table was created with. A fresh install has to declare the same value or the
     * two silently disagree, in a way no column or type comparison can see (#571).</p>
     *
     * <p><strong>When a dimension is appended, this literal does not change.</strong> It is the
     * list as of the moment the rollup was last created from scratch. Growing it alongside
     * {@link #dimensions} is exactly the divergence the freeze exists to prevent, and
     * {@code FlowsSchemaTest} pins each value so the append cannot pass unnoticed.</p>
     */
    private record Rollup(String table, String frozenPrimaryKey, List<Dimension> dimensions) {
    }

    /**
     * A rollup column: its name and type in the target table, and the expression selecting it from
     * {@code flows} in the view. Every expression is alias-qualified so the view never depends on
     * name resolution against the source table.
     */
    private record Dimension(String column, String type, String expression, boolean appended) {

        /**
         * A dimension present when its rollup was created, read straight through from the
         * identically-named source column.
         */
        static Dimension of(final String column, final String type) {
            return new Dimension(column, type, SOURCE_ALIAS + "." + column, false);
        }

        /** A dimension present when its rollup was created, with an expression of its own. */
        Dimension(final String column, final String type, final String expression) {
            this(column, type, expression, false);
        }

        /**
         * A dimension appended to a live rollup after it already held rows.
         *
         * <p>This is what {@link FlowsSchema#appendedDimensions()} selects on, and it is the whole
         * reason that selection is possible. The reserved-default rule applies to these and to
         * nothing else: a dimension present from creation has no rows that predate it, so it has no
         * boundary to protect and may emit its own type default freely. Several do —
         * {@code f.protocol} emits {@code 0} for HOPOPT, {@code f.srcAs} emits {@code 0} for the
         * conventional unknown AS, and {@code f.inputSnmp} emits {@code 0} for an exporter that
         * cannot name an interface. A check applied to every dimension reports all of them, and
         * without this flag there is no way to tell those apart from a real violation.</p>
         */
        static Dimension appended(final String column, final String type) {
            return new Dimension(column, type, SOURCE_ALIAS + "." + column, true);
        }

        /** An appended dimension with an expression of its own. */
        static Dimension appended(final String column, final String type, final String expression) {
            return new Dimension(column, type, expression, true);
        }

        String selectItem() {
            return expression + " AS " + column;
        }

        /**
         * The value this column holds for a row aggregated before the dimension existed — the
         * implicit type default, because a column joining the sorting key may not carry an explicit
         * {@code DEFAULT} (ClickHouse Code 36).
         *
         * <p>That value is the only boundary an appended dimension gets: it is what distinguishes
         * "this row predates the append" from a real reading. It only works while the dimension's
         * SELECT expression can never produce it — see {@link FlowsSchema#appendedDimensions()},
         * which is asserted at build time rather than left to a reviewer noticing.</p>
         */
        String absent() {
            try {
                return reservedValueFor(type);
            } catch (final IllegalArgumentException cause) {
                // reservedValueFor is a function of the type alone, so its message can only name the
                // type. The rule it enforces is about a *dimension*, and a maintainer reading a build
                // failure needs the column to know where to look.
                throw new IllegalArgumentException("rollup dimension " + column + ": " + cause.getMessage(), cause);
            }
        }
    }

    /**
     * One {@code 'name' = number} pair of an enum declaration.
     *
     * <p>Both escape forms are accepted inside a member name. ClickHouse takes {@code ''} on input
     * but renders {@code \'} in {@code SHOW CREATE TABLE} — verified on 26.7, where
     * {@code Enum8('it''s' = 1)} comes back as {@code Enum8('it\'s' = 1)}. Since the rendered form is
     * what a maintainer copies when adding a dimension, understanding only {@code ''} would end a
     * name early: {@code Enum8('\'' = 5)} would parse as the empty name and be waved through as a
     * sentinel it is not.</p>
     */
    private static final Pattern ENUM_MEMBER =
            Pattern.compile("('(?:[^'\\\\]|''|\\\\.)*')\\s*=\\s*(-?\\d+)");

    /**
     * The value a column of this type holds for a row aggregated before it existed, quoted as SQL.
     *
     * <p>Verified against 26.7 rather than assumed, and re-asked of a real server on every run by
     * {@code ReservedValueIT} rather than left to this sentence (#629): a String-ish column defaults
     * to {@code ''}, an IPv6 to {@code '::'}, a DateTime to the epoch, and a numeric to {@code 0}.
     * Collapsing the last
     * three into {@code 0} would publish a reserved value that is not the boundary, and the guard fed
     * by this method would then wave through an expression that genuinely destroys it — {@code
     * srcAddr} and {@code dstAddr} are IPv6 dimensions today.</p>
     *
     * <p>An enum is the case that cannot be answered from the type family alone, and it is the one
     * that fails silently. ClickHouse stores the <strong>smallest-numbered member</strong>, which for
     * the raw table's {@code flowProtocol} is {@code 'NetflowV5'} — a real protocol, indistinguishable
     * from a genuine reading. Reporting {@code 0} for it, as the numeric branch below would, is worse
     * still: {@code 0} is not a value that column can hold at all, so the guard would compare every
     * expression against a value none of them could ever emit and pass all of them.</p>
     *
     * <p>Every member an enum declares is by construction a legitimate value, so an enum has a usable
     * boundary only if it declares a dedicated sentinel as its smallest member. This refuses any that
     * does not, which fails the build through every caller of {@link #appendedDimensions()} rather
     * than needing a guard of its own.</p>
     *
     * @throws IllegalArgumentException if the type is an enum whose reserved value is a real one
     */
    static String reservedValueFor(final String type) {
        // Wrappers first, and by prefix rather than by substring. A wrapper decides the reserved
        // value on its own — verified on 26.7, an appended Nullable(Enum8(…)) or Nullable(String)
        // reads NULL and an Array(…) reads [] — so falling through to the inner type would publish a
        // value the column can never hold, which is the exact failure this method exists to prevent.
        if (type.startsWith("Nullable(")) {
            return "NULL";
        }
        if (type.startsWith("Array(")) {
            return "[]";
        }
        if (type.contains("Enum")) {
            // The smallest member, not the first declared and not the zero member: on 26.7,
            // Enum8('B' = 2, 'A' = 1) reads back 'A' and Enum8('N' = -1, '' = 0, 'P' = 1) reads back
            // 'N'. Either of the other two rules fits flowProtocol, where NetflowV5 = 1 is first and
            // smallest at once, so neither would have been caught by reading this schema alone.
            final Matcher member = ENUM_MEMBER.matcher(type);
            String smallestName = null;
            long smallest = Long.MAX_VALUE;
            while (member.find()) {
                final long value = Long.parseLong(member.group(2));
                if (value < smallest) {
                    smallest = value;
                    smallestName = member.group(1);
                }
            }
            if (smallestName == null) {
                throw new IllegalArgumentException("enum type declares no members: " + type);
            }
            if (!"''".equals(smallestName)) {
                throw new IllegalArgumentException(
                        "enum dimension type " + type + " reserves " + smallestName + " for rows"
                                + " aggregated before it existed, which is a value the column can"
                                + " legitimately hold, so the append would have no boundary — carry"
                                + " the column as LowCardinality(String), or give this ROLLUP column"
                                + " (never the source column on flows) an '' member below "
                                + smallest + ". Adding '' to the source enum instead would make the"
                                + " dimension's own expression emit the reserved value for live"
                                + " rows, which destroys the boundary rather than creating one");
            }
            return smallestName;
        }
        if (type.contains("String")) {
            return "''";
        }
        if (type.contains("IPv6") || type.contains("IPv4")) {
            return "'::'";
        }
        if (type.contains("DateTime") || type.contains("Date")) {
            return "'1970-01-01 00:00:00'";
        }
        return "0";
    }

    /**
     * Every dimension paired with the value that means "aggregated before this dimension existed".
     *
     * <p>The whole set, including dimensions the reserved-default rule does not govern. For the set
     * it does govern, see {@link #appendedDimensions()}.</p>
     *
     * <p>{@code APPLICATION} is the standing counter-example: {@code ifNull(f.application, '')}
     * emits {@code ''} deliberately, because a nullable sort key would break the
     * {@code SummingMergeTree} collapse. That is harmless because it sits inside the frozen primary
     * key of both rollups carrying it, and an appended dimension extends {@code ORDER BY} past the
     * primary key by construction — so it cannot have arrived by append in either.</p>
     */
    public static Map<String, String> everyDimensionWithItsReservedValue() {
        return dimensionsWithReservedValues(dimension -> true);
    }

    /**
     * The dimensions the reserved-default rule actually governs: those appended to a rollup that
     * already held rows, paired with the value those rows read.
     *
     * <p>Selecting on {@code appended} is what makes the rule enforceable. Applied to every
     * dimension it is not merely too strict, it is wrong: {@code f.protocol} emits {@code 0} for
     * HOPOPT and {@code f.srcAs} emits {@code 0} for the conventional unknown AS, and both are
     * correct readings on a dimension that has no pre-append rows to distinguish them from.</p>
     *
     * <p>Two entries today: {@code samplingInterval} (#470) and {@code flowProtocol} (#583).</p>
     */
    public static Map<String, String> appendedDimensions() {
        return dimensionsWithReservedValues(Dimension::appended);
    }

    private static Map<String, String> dimensionsWithReservedValues(
            final java.util.function.Predicate<Dimension> select) {
        final Map<String, String> byExpression = new LinkedHashMap<>();
        for (final Rollup rollup : ROLLUPS) {
            for (final Dimension dimension : allDimensions(rollup)) {
                if (select.test(dimension)) {
                    byExpression.put(dimension.expression(), dimension.absent());
                }
            }
        }
        return Collections.unmodifiableMap(byExpression);
    }

    /** An aggregate carried by every rollup: its target column and the aggregating expression. */
    private record Measure(String column, String expression) {
    }

    // Placeholder tokens substituted with the qualified names / TTL. Plain replace() (not
    // String.format) avoids treating the multi-line DDL as a format string.
    private static final String FLOWS_TOKEN = "@@flows@@";
    private static final String SAMPLES_TOKEN = "@@samples@@";
    private static final String TTL_DAYS_TOKEN = "@@ttlDays@@";

    @Language("ClickHouse")
    private static final String FLOWS_TABLE = """
        CREATE TABLE IF NOT EXISTS @@flows@@ (
            -- Time columns pin the UTC timezone so the stored instants also display and parse in
            -- UTC — the schema is timezone-explicit, not dependent on the server's local zone (#276).
            timestamp DateTime64(3, 'UTC'),

            flowProtocol Enum8(
                'NetflowV5' = 1,
                'NetflowV9' = 2,
                'IPFIX' = 3,
                'SFLOW' = 4
            ),

            tenant String,
            organisation String,
            zone String,
            system String,
            exporterAddr String,

            receivedAt DateTime64(9, 'UTC'),

            firstSwitched DateTime64(9, 'UTC'),
            deltaSwitched DateTime64(9, 'UTC'),
            lastSwitched DateTime64(9, 'UTC'),

            inputSnmp UInt32,
            inputSnmpIfName Nullable(String),
            inputSnmpIfAlias Nullable(String),
            inputSnmpIfSpeed Nullable(UInt32),

            outputSnmp UInt32,
            outputSnmpIfName Nullable(String),
            outputSnmpIfAlias Nullable(String),
            outputSnmpIfSpeed Nullable(UInt32),

            srcAs UInt64,
            srcAsOrg Nullable(String),
            srcAddr IPv6,
            srcMaskLen UInt8,
            srcAddrHostname Nullable(String),
            srcPort UInt16,

            dstAs UInt64,
            dstAsOrg Nullable(String),
            dstAddr IPv6,
            dstMaskLen UInt8,
            dstAddrHostname Nullable(String),
            dstPort UInt16,

            nextHop Nullable(IPv6),
            nextHopHostname Nullable(String),

            bytes UInt64,
            packets UInt64,

            direction Enum8('INGRESS' = 1, 'EGRESS' = 2, 'UNKNOWN' = 3),

            engineId UInt32,
            engineType UInt16,

            vlan UInt16,
            ipProtocolVersion UInt8,
            protocol UInt8,
            tcpFlags UInt8,
            tos UInt8,

            samplingAlgorithm Enum8(
                'Unassigned' = 1,
                'SystematicCountBasedSampling' = 2,
                'SystematicTimeBasedSampling' = 3,
                'RandomNOutOfNSampling' = 4,
                'UniformProbabilisticSampling' = 5,
                'PropertyMatchFiltering' = 6,
                'HashBasedFiltering' = 7,
                'FlowStateDependentIntermediateFlowSelectionProcess' = 8
            ),\s
            samplingInterval Float64,

            application Nullable(String),

            srcLocality Enum8('PUBLIC' = 1, 'PRIVATE' = 2),
            dstLocality Enum8('PUBLIC' = 1, 'PRIVATE' = 2),
            flowLocality Enum8('PUBLIC' = 1, 'PRIVATE' = 2),

            clockCorrection Nullable(Int64),

            -- Additive columns (0.5.x); '' = unknown. Kept last so a pre-existing table
            -- upgraded via addAdditiveColumns() has the same column order as a fresh one.
            srcCountry LowCardinality(String),
            srcCity LowCardinality(String),
            dstCountry LowCardinality(String),
            dstCity LowCardinality(String),
            exporterName LowCardinality(String),

            -- Which rung of the resolution ladder supplied samplingInterval: 'record', 'options',
            -- 'header', 'derived', 'fallback' or 'assumed'. '' means the row was written before
            -- this column existed, which is distinct from 'assumed' and is not backfillable.
            samplingProvenance LowCardinality(String)
        ) ENGINE = MergeTree()
        -- PRIMARY KEY declared, not derived, for the reason spelled out at rollupTable(): a later
        -- MODIFY ORDER BY appends to the sorting key alone, and a derived primary key would leave
        -- upgraded and fresh installs disagreeing. The two lists are equal today; if this key ever
        -- gains a column, add it to ORDER BY ONLY and leave PRIMARY KEY frozen here (#470).
        PRIMARY KEY (
            tenant, organisation,
            toStartOfHour(timestamp),
            srcAs, dstAs,
            srcAddr, dstAddr,
            srcPort, dstPort
        )
        ORDER BY (
            tenant, organisation,
            toStartOfHour(timestamp),
            srcAs, dstAs,
            srcAddr, dstAddr,
            srcPort, dstPort
        )
        PARTITION BY toYYYYMMDD(timestamp)
        TTL toDateTime(timestamp) + INTERVAL @@ttlDays@@ DAY
        SETTINGS index_granularity = 8192;
    """;

    @Language("ClickHouse")
    private static final String SAMPLES_VIEW = """
        CREATE OR REPLACE VIEW @@samples@@ AS
        WITH
            toInt64({ival:Int64} * 1000000000) AS interval_ns,

            -- Determine the fraction of the interval used in each case. Computed from the
            -- per-flow scalars the inner subquery materialized: as plain WITH aliases over the
            -- base columns these expressions re-expand per reference through the ARRAY JOIN,
            -- which made every query over the view 5-15x slower (issue #346) — keep the scalar
            -- math in the subquery and only shallow arithmetic here.
            CASE
                -- Only one bucket
                WHEN bucket_count = 1
                    THEN 1.0

                -- First bucket: Portion from start time to the next bucket boundary
                WHEN bucket = 0
                    THEN ((interval_ns * (first_bucket + 1)) - delta_ns) / interval_ns

                -- Last bucket: Portion from the start of the last bucket to end time
                WHEN bucket = bucket_count - 1
                    THEN (last_ns - (interval_ns * last_bucket)) / interval_ns

                -- Full buckets in between
                ELSE 1.0
                END AS bucket_fraction,

            -- Each bucket receives the flow's share proportional to the TIME spent in that bucket
            -- (fraction of the interval, normalized by the flow duration), so bytes/packets are
            -- conserved: summing over all buckets returns the flow's exact totals. Dividing by
            -- bucket_count instead would under-report by duration/(ival * buckets) (issue #270).
            -- The bucket_count = 1 branch is the division guard for zero-duration flows — do not
            -- fold it into bucket_fraction.
            if(bucket_count = 1, 1., bucket_fraction * interval_ns / flow_duration) AS bucket_share

        SELECT
            -- EXCEPT hides the helper scalars below. The raw timestamp/bytes/packets stay
            -- addressable under their flow.-qualified names (and appear so in SELECT *) — that is
            -- pre-existing analyzer behavior, and flow.timestamp is exactly what a partition-
            -- pruning time bound must reference (see the query-performance docs).
            flow.* EXCEPT (delta_ns, last_ns, first_bucket, last_bucket, bucket_count, flow_duration),

            fromUnixTimestamp64Nano((first_bucket + bucket) * interval_ns) AS timestamp,

            bytes * bucket_share AS bytes,
            packets * bucket_share AS packets

        FROM (
            SELECT
                *,

                toUnixTimestamp64Nano(deltaSwitched) AS delta_ns,
                toUnixTimestamp64Nano(lastSwitched) AS last_ns,

                -- Find first and last absolute bucket numbers. Integer division is load-bearing:
                -- Float64 '/' cannot represent nanosecond epochs exactly (ULP ~256ns), which would
                -- absorb the boundary shift below. greatest() clamps corrupt flows with
                -- lastSwitched < deltaSwitched to one bucket — unclamped, bucket_count wraps and
                -- range() throws, poisoning every query over the view. The 1ns shift moves a flow
                -- ending exactly on a bucket boundary into the preceding bucket instead of emitting
                -- a spurious zero-contribution bucket.
                toUInt64(intDiv(delta_ns, interval_ns)) AS first_bucket,
                toUInt64(intDiv(greatest(last_ns, delta_ns) - if(last_ns > delta_ns, 1, 0),
                                interval_ns)) AS last_bucket,

                last_bucket - first_bucket + 1 AS bucket_count,

                -- Total duration in nanoseconds; negative for corrupt flows, which never divide
                -- by it (they clamp to bucket_count = 1 above).
                last_ns - delta_ns AS flow_duration

            FROM @@flows@@
        ) AS flow
        -- No ORDER BY: callers that need an order state it themselves. Aggregating consumers get
        -- the sort eliminated by the optimizer either way, but a bare SELECT would pay a full sort
        -- of the exploded set (~rows x buckets) for an ordering nothing relies on.
        ARRAY JOIN range(toUInt32(bucket_count)) AS bucket;
    """;
}
