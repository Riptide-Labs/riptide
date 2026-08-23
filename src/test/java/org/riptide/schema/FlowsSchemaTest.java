/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.schema;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.google.common.base.Splitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The flow schema DDL is the single source shared by the collector's manage path and {@code
 * onboard}, so the load-bearing properties are that it is database-qualified (works on an unpinned
 * client), idempotent, and that the {@code samples} view still references the same qualified table.
 *
 * <p>The rollups add a second set: the dimension/measure split must line up exactly with the
 * {@code SummingMergeTree} sort key, and their column names and time semantics must match the raw
 * table closely enough that a query ports between the two.
 */
class FlowsSchemaTest {

    @Test
    void createDatabaseIsIdempotentAndQuoted() {
        assertThat(FlowsSchema.createDatabase("riptide"))
                .isEqualTo("CREATE DATABASE IF NOT EXISTS `riptide`");
    }

    @Test
    void createFlowsTableIsQualifiedAndIdempotent() {
        final String ddl = FlowsSchema.createFlowsTable("riptide");
        assertThat(ddl.strip()).startsWith("CREATE TABLE IF NOT EXISTS `riptide`.flows (");
        // A representative column and the engine/partitioning survive the extraction unchanged.
        assertThat(ddl)
                .contains("tenant String,")
                .contains("clockCorrection Nullable(Int64)")
                .contains("ENGINE = MergeTree()")
                .contains("PARTITION BY toYYYYMMDD(timestamp)");
    }

    /**
     * The additive columns are the ones an existing table can gain in place. Both emissions come
     * off one map, so a fresh table and an upgraded one end up with the same columns in the same
     * order — the property that keeps the two shapes from diverging over successive upgrades.
     */
    @Test
    void additiveColumnsAppearInBothTheCreateAndTheAlterPath() {
        final String create = FlowsSchema.createFlowsTable("riptide");
        final List<String> alters = FlowsSchema.addAdditiveColumns("riptide");

        assertThat(FlowsSchema.additiveColumnNames()).contains("samplingProvenance");
        assertThat(create).contains("samplingProvenance LowCardinality(String)");
        assertThat(alters).contains(
                "ALTER TABLE `riptide`.flows ADD COLUMN IF NOT EXISTS samplingProvenance LowCardinality(String)");

        // Every additive column is emitted by both paths, in the same relative order.
        final var declarationOrder = FlowsSchema.additiveColumnNames().stream()
                .sorted(java.util.Comparator.comparingInt(create::indexOf))
                .toList();
        final var alterOrder = alters.stream()
                .map(statement -> statement.replaceAll(".*ADD COLUMN IF NOT EXISTS (\\w+).*", "$1"))
                .toList();
        assertThat(alterOrder).containsExactlyElementsOf(declarationOrder);
    }

    /**
     * A {@code LowCardinality(String)}, not an {@code Enum8}: the additive path can only add a
     * column, so a rung added to the vocabulary later must not require an {@code ALTER … MODIFY}.
     */
    @Test
    void provenanceIsAStringSoTheRungSetCanGrow() {
        assertThat(FlowsSchema.createFlowsTable("riptide"))
                .contains("samplingProvenance LowCardinality(String)")
                .doesNotContain("samplingProvenance Enum8");
    }

    @Test
    void timeColumnsPinUtcTimezone() {
        // The schema is timezone-explicit so stored instants display/parse in UTC regardless of the
        // server's local zone (#276) — every time column carries the 'UTC' timezone argument.
        final String ddl = FlowsSchema.createFlowsTable("riptide");
        assertThat(ddl)
                .contains("timestamp DateTime64(3, 'UTC')")
                .contains("receivedAt DateTime64(9, 'UTC')")
                .contains("firstSwitched DateTime64(9, 'UTC')")
                .contains("deltaSwitched DateTime64(9, 'UTC')")
                .contains("lastSwitched DateTime64(9, 'UTC')");
    }

    @Test
    void createSamplesViewQualifiesBothViewAndSourceTable() {
        final String ddl = FlowsSchema.createSamplesView("riptide");
        assertThat(ddl.strip()).startsWith("CREATE OR REPLACE VIEW `riptide`.samples AS");
        // One pattern spanning FROM through the alias: the qualified flows table must be what the
        // scalar-projecting subquery aliased AS flow reads — two independent contains() would
        // also pass with the fragments in unrelated clauses.
        assertThat(ddl).containsPattern("FROM `riptide`\\.flows\\s*\\)\\s*AS flow");
        // The view parameter is a literal placeholder bound at SELECT time, not at CREATE time.
        assertThat(ddl).contains("{ival:Int64}");
    }

    @Test
    void qualifiesToTheGivenDatabase() {
        assertThat(FlowsSchema.createFlowsTable("acme_prod"))
                .contains("CREATE TABLE IF NOT EXISTS `acme_prod`.flows (");
        assertThat(FlowsSchema.createSamplesView("acme_prod"))
                .contains("VIEW `acme_prod`.samples AS")
                .containsPattern("FROM `acme_prod`\\.flows\\s*\\)\\s*AS flow");
        assertThat(FlowsSchema.qualifiedFlows("acme_prod")).isEqualTo("`acme_prod`.flows");
    }

    @Test
    void ttlIsParameterizedAndDefaultsToTheCollectorRetention() {
        assertThat(FlowsSchema.createFlowsTable("riptide", 400))
                .contains("TTL toDateTime(timestamp) + INTERVAL 400 DAY");
        // The single-arg overload (the collector's manage path) keeps the historical 30 days.
        assertThat(FlowsSchema.createFlowsTable("riptide"))
                .contains("TTL toDateTime(timestamp) + INTERVAL 30 DAY");
    }

    /**
     * #470: the primary key is declared, never derived. This DDL assertion is the ONLY pin for
     * that — the change has no runtime observable, because a derived primary key equals the
     * sorting key, which is exactly why today's schema is safe. Asserting
     * {@code primary_key = sorting_key} against a live ClickHouse passes with the clause, without
     * it, and after someone deletes it.
     *
     * <p>Why it matters: {@code ALTER TABLE … MODIFY ORDER BY} appends to the sorting key alone.
     * Derived, an upgraded install would keep an N-column primary key under an N+1-column sorting
     * key while a fresh install derived N+1 for both — verified on ClickHouse 26.7 — and no
     * column-name or type comparison can see it.</p>
     */
    @Test
    void rollupTablesDeclareThePrimaryKeyRatherThanDerivingIt() {
        // The frozen value per rollup. This literal IS the mechanism: appending a dimension makes
        // rollupTable() grow both clauses, which is the divergence — an upgraded install cannot
        // grow its primary key to match, because ALTER … MODIFY ORDER BY touches only the sorting
        // key. Failing here forces that append to be a conscious decision: freeze the primary key
        // (leaving this map untouched) rather than letting it follow the sorting key.
        final Map<String, String> frozenPrimaryKeys = Map.of(
                "flows_by_application_1m",
                "tenant, organisation, timestamp, zone, application, protocol",
                "flows_by_conversation_1m",
                "tenant, organisation, timestamp, zone, srcAddr, dstAddr, application",
                "flows_by_exporter_iface_1m",
                "tenant, organisation, timestamp, zone, exporterAddr, exporterName, inputSnmp, outputSnmp",
                "flows_by_geo_asn_1m",
                "tenant, organisation, timestamp, zone, srcAs, dstAs, srcCountry, dstCountry");

        for (final String ddl : FlowsSchema.createRollupTables("riptide")) {
            assertThat(ddl).as("a derived primary key is the defect").contains("PRIMARY KEY (");
            // PREFIX, not equality: ClickHouse's own rule is prefix, and equality would forbid the
            // freeze this change exists to enable — when a dimension is appended, ORDER BY grows
            // and the primary key must stay put.
            assertThat(keyList(ddl, "ORDER BY ("))
                    .as("primary key must be a prefix of the sorting key")
                    .startsWith(keyList(ddl, "PRIMARY KEY ("));

            final int from = ddl.indexOf("CREATE TABLE IF NOT EXISTS ") + "CREATE TABLE IF NOT EXISTS ".length();
            final String table = ddl.substring(from, ddl.indexOf(" (", from)).replace("`riptide`.", "").trim();
            assertThat(keyList(ddl, "PRIMARY KEY ("))
                    .as("%s's primary key is frozen; grow ORDER BY alone when adding a dimension", table)
                    .isEqualTo(frozenPrimaryKeys.get(table));
        }
    }

    /** The same rule on the flows table, whose key is typed out twice in a template. */
    @Test
    void flowsTableDeclaresThePrimaryKeyRatherThanDerivingIt() {
        final String ddl = FlowsSchema.createFlowsTable("riptide");
        assertThat(ddl).contains("PRIMARY KEY (");
        assertThat(keyList(ddl, "ORDER BY ("))
                .as("primary key must be a prefix of the sorting key")
                .startsWith(keyList(ddl, "PRIMARY KEY ("));
        // and pinned to the literal, because a prefix assertion alone passes if BOTH lists shrink
        // together — nothing else in the suite would notice the flows sort key losing a column
        assertThat(keyList(ddl, "PRIMARY KEY (")).isEqualTo(
                "tenant, organisation, toStartOfHour(timestamp), srcAs, dstAs, "
                        + "srcAddr, dstAddr, srcPort, dstPort");
    }

    @Test
    void rollupTablesSummingEveryDimensionIntoTheSortKey() {
        // SummingMergeTree collapses rows that agree on the sort key, summing the rest. That is
        // only correct if the split is exact: every dimension in the key, every measure out of it.
        final List<String> measures =
                List.of("bytes", "packets", "flowCount", "bytesIn", "bytesOut", "packetsIn", "packetsOut");
        for (final String ddl : FlowsSchema.createRollupTables("riptide")) {
            assertThat(ddl).contains("ENGINE = SummingMergeTree()");
            final String sortKey = between(ddl, "ORDER BY (", ")");
            for (final String column : columnsOf(ddl)) {
                if (measures.contains(column)) {
                    assertThat(sortKey).as("measure %s must not be in the sort key", column)
                            .doesNotContain(column);
                } else {
                    assertThat(sortKey).as("dimension %s must be in the sort key of %s", column, ddl)
                            .contains(column);
                }
            }
        }
    }

    @Test
    void rollupViewsQualifyEverySourceReference() {
        // Every expression is alias-qualified, so the view never depends on name resolution against
        // the source table — an unqualified sum(bytes) would break the moment a column is added.
        for (final String ddl : FlowsSchema.createRollupViews("riptide")) {
            assertThat(ddl)
                    .contains("FROM `riptide`.flows AS f")
                    .contains("sum(f.bytes) AS bytes")
                    .contains("sumIf(f.bytes, f.direction = 'INGRESS') AS bytesIn")
                    .doesNotContain("sum(bytes)")
                    .doesNotContain("sumIf(bytes");
        }
    }

    @Test
    void rollupsKeepUndirectedTotalsAlongsideTheDirectionSplit() {
        // A query that does not care about direction should not have to add bytesIn + bytesOut.
        assertThat(FlowsSchema.createRollupViews("riptide").getFirst())
                .contains("sum(f.bytes) AS bytes")
                .contains("sum(f.packets) AS packets")
                .contains("count() AS flowCount");
    }

    @Test
    void rollupTimeColumnMatchesTheRawTableSoTimeFilterPortsUnchanged() {
        // Same column name as flows, truncated to the minute: a WHERE on timestamp moves between
        // raw and rollup without rewriting.
        assertThat(FlowsSchema.createRollupTables("riptide").getFirst())
                .contains("timestamp DateTime('UTC')")
                .contains("PARTITION BY toYYYYMM(timestamp)");
        assertThat(FlowsSchema.createRollupViews("riptide").getFirst())
                .contains("toStartOfMinute(f.timestamp) AS timestamp");
    }

    @Test
    void rollupApplicationIsNonNullableBecauseItIsASortKey() {
        // application is Nullable(String) on flows; folded to '' on the way in so the sort key —
        // and therefore the SummingMergeTree collapse — never depends on null comparison.
        assertThat(FlowsSchema.createRollupTables("riptide").getFirst())
                .contains("application LowCardinality(String)");
        assertThat(FlowsSchema.createRollupViews("riptide").getFirst())
                .contains("ifNull(f.application, '') AS application");
    }

    @Test
    void rollupDdlIsQualifiedIdempotentAndOrderedTargetsBeforeViews() {
        assertThat(FlowsSchema.rollupTableNames()).containsExactly(
                "flows_by_application_1m",
                "flows_by_conversation_1m",
                "flows_by_exporter_iface_1m",
                "flows_by_geo_asn_1m");
        assertThat(FlowsSchema.createRollupTables("acme_prod")).allSatisfy(ddl ->
                assertThat(ddl).startsWith("CREATE TABLE IF NOT EXISTS `acme_prod`."));
        assertThat(FlowsSchema.createRollupViews("acme_prod")).allSatisfy(ddl ->
                assertThat(ddl).startsWith("CREATE MATERIALIZED VIEW IF NOT EXISTS `acme_prod`.")
                        .contains(" TO `acme_prod`."));
        assertThat(FlowsSchema.qualifiedRollup("acme_prod", "flows_by_application_1m"))
                .isEqualTo("`acme_prod`.flows_by_application_1m");
        assertThat(FlowsSchema.qualifiedRollupView("acme_prod", "flows_by_application_1m"))
                .isEqualTo("`acme_prod`.flows_by_application_1m_mv");
    }

    @Test
    void rollupTtlIsParameterizedAndOutlivesTheRawTable() {
        assertThat(FlowsSchema.createRollupTables("riptide", 90).getFirst())
                .contains("TTL timestamp + INTERVAL 90 DAY");
        assertThat(FlowsSchema.createRollupTables("riptide").getFirst())
                .contains("TTL timestamp + INTERVAL 365 DAY");
        // The rollups exist so long-range queries survive the raw table's expiry — which only
        // works while the rollup retention is the longer of the two.
        assertThat(FlowsSchema.DEFAULT_ROLLUP_TTL_DAYS).isGreaterThan(FlowsSchema.DEFAULT_TTL_DAYS);
    }

    @Test
    void identRejectsUnsafeDatabaseNames() {
        // The collector's riptide.clickhouse.database binds without validation, so the quoting
        // site enforces the charset — a backtick must fail clearly, not emit malformed DDL.
        assertThatThrownBy(() -> FlowsSchema.createDatabase("ript`ide"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ript`ide");
        assertThatThrownBy(() -> FlowsSchema.createFlowsTable("a;b"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FlowsSchema.createSamplesView(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FlowsSchema.createRollupTables("a b"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FlowsSchema.createRollupViews("a'b"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static final Splitter COLUMN_SPLITTER = Splitter.on(Pattern.compile("\\s+"));

    /** The column names of a CREATE TABLE body, in declaration order. */
    private static List<String> columnsOf(final String ddl) {
        return between(ddl, "(\n", "\n) ENGINE").lines()
                .map(line -> COLUMN_SPLITTER.split(line.strip()).iterator().next())
                .filter(name -> !name.isEmpty())
                .toList();
    }

    /**
     * The full parenthesised list after {@code marker}, honouring nesting. {@link #between} stops
     * at the first {@code ")"}, which inside the flows key is the one closing
     * {@code toStartOfHour(timestamp)} — so a comparison built on it silently compares prefixes
     * and cannot see a dropped trailing column. A mutation caught exactly that.
     */
    private static String keyList(final String text, final String marker) {
        final int open = text.indexOf(marker) + marker.length();
        int depth = 1;
        for (int i = open; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return text.substring(open, i).replaceAll("\\s+", " ").strip();
            }
        }
        throw new AssertionError("unbalanced parentheses after " + marker);
    }

    private static String between(final String text, final String open, final String close) {
        final int from = text.indexOf(open) + open.length();
        return text.substring(from, text.indexOf(close, from));
    }

    /**
     * The reserved-default rule, enforced rather than remembered (#470 §2).
     *
     * <p>An appended dimension's only boundary is its type default: the value a row aggregated
     * before the append reads, and which a column joining the sorting key cannot be given an
     * explicit {@code DEFAULT} for. That boundary exists only while the dimension's expression
     * cannot itself emit that value.</p>
     *
     * <p>{@code ifNull(f.application, '')} is the standing exception. It emits {@code ''}
     * deliberately — a nullable sort key would break the {@code SummingMergeTree} collapse — and is
     * harmless only because it predates every append. This test names it, so a second one cannot be
     * added quietly: the day someone writes {@code ifNull(f.srcCity, '')}, the build says no and
     * points at {@code 'unknown'} instead.</p>
     */
    @Test
    void noDimensionAddedLaterMayEmitTheValueThatMarksItsOwnAbsence() {
        final Set<String> allowed = Set.of("ifNull(f.application, '')");

        assertThat(FlowsSchema.appendableDimensions())
                .allSatisfy((expression, absent) -> {
                    if (allowed.contains(expression)) {
                        return;
                    }
                    assertThat(expression)
                            .as("%s can emit %s, which is the value that marks a pre-append row",
                                    expression, absent)
                            .doesNotContain(", " + absent + ")");
                });
    }

    /** The append and the reorder must be one statement, or ClickHouse rejects it with Code 36. */
    @Test
    void theRepairAddsColumnsAndReordersInOneStatement() {
        assertThat(FlowsSchema.alterRollupTargets("riptide"))
                .hasSize(4)
                .allSatisfy((rollup, ddl) -> {
                    assertThat(ddl).startsWith("ALTER TABLE `riptide`." + rollup);
                    assertThat(ddl).contains("ADD COLUMN IF NOT EXISTS").contains("MODIFY ORDER BY (");
                    assertThat(ddl.split("ALTER TABLE")).as("one statement, not several").hasSize(2);
                });
    }

    /**
     * A column joining the sorting key may not carry a DEFAULT — ClickHouse: "Newly added column X
     * has a default expression, so adding expressions that use it to the sorting key is forbidden."
     * The implicit type default is what marks pre-append rows, so this is load-bearing twice over.
     */
    @Test
    void theRepairNeverGivesANewSortKeyColumnADefault() {
        assertThat(FlowsSchema.alterRollupTargets("riptide"))
                .allSatisfy((rollup, ddl) -> assertThat(ddl).doesNotContain("DEFAULT"));
    }

    /** The repaired sorting key must equal the one a fresh CREATE would use, or the two diverge. */
    @Test
    void theRepairedSortKeyMatchesWhatAFreshInstallCreates() {
        final Map<String, String> intended = FlowsSchema.rollupSortKeys();
        for (final String ddl : FlowsSchema.createRollupTables("riptide")) {
            final int from = ddl.indexOf("CREATE TABLE IF NOT EXISTS ") + "CREATE TABLE IF NOT EXISTS ".length();
            final String table = ddl.substring(from, ddl.indexOf(" (", from)).replace("`riptide`.", "").trim();
            assertThat(keyList(ddl, "ORDER BY (")).isEqualTo(intended.get(table));
        }
    }

    /**
     * A repaired view and a freshly created one must select identically, or every repair would be
     * reported as drift on the very next start.
     */
    @Test
    void theRepairedViewSelectsExactlyWhatAFreshOneDoes() {
        final Map<String, String> modifies = FlowsSchema.modifyRollupViews("riptide");
        FlowsSchema.rollupSelects("riptide").forEach((rollup, select) ->
                assertThat(modifies.get(rollup))
                        .isEqualTo("ALTER TABLE `riptide`." + rollup + "_mv MODIFY QUERY\n" + select));
    }

    /**
     * The mechanism, pinned here because the integration test cannot pin it: swapping
     * {@code MODIFY QUERY} for {@code DROP} + {@code CREATE} leaves the mid-stream loss test green,
     * since the two statements run back-to-back and nothing lands in the gap at test cadence. The
     * whole change rests on the view never being absent, so the absence of a DROP is asserted
     * directly.
     */
    @Test
    void noRepairStatementEverDropsAnything() {
        assertThat(FlowsSchema.modifyRollupViews("riptide"))
                .allSatisfy((rollup, ddl) -> assertThat(ddl)
                        .as("a dropped view does not backfill; the gap is a permanent hole")
                        .doesNotContainIgnoringCase("DROP")
                        .contains("MODIFY QUERY"));
        assertThat(FlowsSchema.alterRollupTargets("riptide"))
                .allSatisfy((rollup, ddl) -> assertThat(ddl).doesNotContainIgnoringCase("DROP"));
    }

    /**
     * #571's frozen primary keys already catch a dimension inserted mid-list, because the primary
     * key is the full dimension list. What was missing is the reason, so the failure explains
     * itself rather than reading as a stale fixture.
     */
    @Test
    void aMidListDimensionIsCaughtByTheFrozenPrimaryKeys() {
        final Map<String, String> intended = FlowsSchema.rollupSortKeys();
        for (final String ddl : FlowsSchema.createRollupTables("riptide")) {
            assertThat(intended.values())
                    .as("a dimension inserted mid-list works on a fresh install and is impossible on"
                            + " an upgraded one — ALTER can only append — so the build is the only"
                            + " place it can be caught")
                    .anySatisfy(key -> assertThat(keyList(ddl, "ORDER BY (")).isEqualTo(key));
        }
    }
}
