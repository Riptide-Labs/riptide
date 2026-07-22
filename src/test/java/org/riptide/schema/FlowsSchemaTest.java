/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.schema;

import org.junit.jupiter.api.Test;

import java.util.List;

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

    /** The column names of a CREATE TABLE body, in declaration order. */
    private static List<String> columnsOf(final String ddl) {
        return between(ddl, "(\n", "\n) ENGINE").lines()
                .map(line -> line.strip().split(" ")[0])
                .filter(name -> !name.isEmpty())
                .toList();
    }

    private static String between(final String text, final String open, final String close) {
        final int from = text.indexOf(open) + open.length();
        return text.substring(from, text.indexOf(close, from));
    }
}
