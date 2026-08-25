/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.schema;

import org.junit.jupiter.api.Test;
import org.riptide.mcp.service.QueryRouter;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
        // The literal a fallback yields, whatever the spacing: ifNull(x,''), coalesce(x , '') and
        // ifNull(x, '') all reduce to the same thing. An earlier version compared the raw substring
        // ", '')" and would have waved through every spelling but one.
        final Pattern fallback = Pattern.compile(",\\s*('[^']*'|[0-9.]+)\\s*\\)\\s*$");

        FlowsSchema.appendableDimensions().forEach((expression, absent) -> {
            if (allowed.contains(expression)) {
                return;
            }
            final Matcher matcher = fallback.matcher(expression);
            if (matcher.find()) {
                assertThat(matcher.group(1))
                        .as("%s falls back to %s, which is the value that marks a pre-append row —"
                                + " use a sentinel the dimension cannot otherwise hold", expression, absent)
                        .isNotEqualTo(absent);
            }
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
     * The repair planner, exercised where it matters: as column lists, not strings.
     *
     * <p>{@code "tenant, srcAsn"} starts with {@code "tenant, srcAs"} as text, so a raw prefix test
     * reads a renamed trailing dimension as an append. The ALTER then fails on the server during
     * startup, taking the collector down instead of the warn-and-leave-alone path.</p>
     */
    @Test
    void aRenamedDimensionIsRefusedRatherThanReadAsAnAppend() {
        final String rollup = FlowsSchema.ROLLUP_BY_GEO_ASN;
        final String live = "tenant, organisation, timestamp, zone, srcAsn";

        final var plan = FlowsSchema.planRollupRepair(
                Map.of(rollup, live), Map.of(rollup, Set.of(live.split(", "))));

        assertThat(plan.repair()).isEmpty();
        assertThat(plan.refused()).containsKey(rollup);
        assertThat(plan.refused().get(rollup)).contains("not an append");
    }

    /** A genuine append is planned. */
    @Test
    void aShorterLiveKeyThatIsAPrefixIsAnAppend() {
        final String rollup = FlowsSchema.ROLLUP_BY_APPLICATION;
        final String live = "tenant, organisation, timestamp, zone, application";

        final var plan = FlowsSchema.planRollupRepair(
                Map.of(rollup, live), Map.of(rollup, Set.of(live.split(", "))));

        assertThat(plan.repair()).containsExactly(rollup);
        assertThat(plan.refused()).isEmpty();
    }

    /** A shrink is refused here, because the server stopped rejecting it once #571 froze the key. */
    @Test
    void aSortKeyThatWouldShrinkIsRefused() {
        final String rollup = FlowsSchema.ROLLUP_BY_APPLICATION;
        final String live = FlowsSchema.rollupSortKeys().get(rollup) + ", srcCity";

        final var plan = FlowsSchema.planRollupRepair(
                Map.of(rollup, live), Map.of(rollup, Set.of(live.split(", "))));

        assertThat(plan.repair()).isEmpty();
        assertThat(plan.refused()).containsKey(rollup);
    }

    /**
     * A rollup already at this version's shape plans nothing — including when a measure is missing,
     * which this path cannot add. Planning it would log an identical-keys repair on every boot
     * forever and never converge.
     */
    @Test
    void aCurrentRollupPlansNothingEvenWithAMeasureMissing() {
        final String rollup = FlowsSchema.ROLLUP_BY_APPLICATION;
        final String live = FlowsSchema.rollupSortKeys().get(rollup);

        assertThat(FlowsSchema.planRollupRepair(
                Map.of(rollup, live), Map.of(rollup, Set.of(live.split(", ")))).repair())
                .as("measures are out of scope; a missing one must not plan an endless repair")
                .isEmpty();
    }

    /** A rollup the connecting user cannot see is left alone, not guessed at. */
    @Test
    void anInvisibleRollupIsNotPlanned() {
        assertThat(FlowsSchema.planRollupRepair(Map.of(), Map.of()).repair()).isEmpty();
        assertThat(FlowsSchema.planRollupRepair(Map.of(), Map.of()).refused()).isEmpty();
    }

    /** The reserved value has to be the column's real type default, or the guard above is blind. */
    @Test
    void theReservedValueIsTheActualTypeDefault() {
        final Map<String, String> byExpression = FlowsSchema.appendableDimensions();
        assertThat(byExpression.get("f.srcAddr"))
                .as("IPv6 defaults to '::', not 0 — srcAddr and dstAddr are IPv6 dimensions today")
                .isEqualTo("'::'");
        assertThat(byExpression.get("toStartOfMinute(f.timestamp)"))
                .as("DateTime defaults to the epoch, not 0")
                .isEqualTo("'1970-01-01 00:00:00'");
        assertThat(byExpression.get("f.protocol")).isEqualTo("0");
        assertThat(byExpression.get("f.tenant")).isEqualTo("''");
        assertThat(byExpression.get("toString(f.flowProtocol)"))
                .as("the protocol is carried as a string precisely so it reserves '' rather than a"
                        + " real protocol name")
                .isEqualTo("''");
    }

    /**
     * An enum reserves its <strong>smallest-numbered</strong> member, and nothing weaker will do.
     *
     * <p>Driven through {@link FlowsSchema#reservedValueFor} with literal type strings rather than
     * over the live dimensions, because no dimension is an enum today: a test walking {@code ROLLUPS}
     * would assert over an empty set and pass no matter what the rule became. The riptide enum cannot
     * distinguish the candidate rules either — {@code NetflowV5 = 1} is the first declared, the
     * smallest, and the only one adjacent to zero all at once — so the cases below are built to
     * separate them, and each was checked against a real 26.7 server before being pinned here.</p>
     */
    @Test
    void anEnumReservesItsSmallestMemberNotItsFirstAndNotItsZero() {
        assertThat(FlowsSchema.reservedValueFor("Enum8('' = 0, 'X' = 5)"))
                .as("the sentinel is the smallest, so this enum has a boundary")
                .isEqualTo("''");

        // "reserves 'A'", not "'A'". The message echoes the whole type, so every member name appears
        // in it whatever the rule picked: asserting the bare name passes for any of them. A mutation
        // taking the first-declared member instead of the smallest survived that weaker assertion.
        assertThatThrownBy(() -> FlowsSchema.reservedValueFor("Enum8('B' = 2, 'A' = 1)"))
                .as("'B' is declared first but 'A' is smaller, so first-declared is not the rule")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserves 'A'");

        assertThatThrownBy(() -> FlowsSchema.reservedValueFor("Enum8('N' = -1, '' = 0, 'P' = 1)"))
                .as("a zero member exists and is still not what the server stores, so"
                        + " zero-if-present is not the rule either")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserves 'N'");
    }

    /**
     * The raw table's own {@code flowProtocol} enum is refused as a rollup dimension type.
     *
     * <p>This is the mutation the change exists to prevent. Typed as {@code Enum8('NetflowV5' = 1,
     * …)}, every row aggregated before the append reads back as {@code NetflowV5}: a valid protocol,
     * indistinguishable from a real one, which {@code != 'SFLOW'} then admits — re-inflating exactly
     * the sFlow traffic the column was added to correct. Nothing in the data would mark it.</p>
     */
    @Test
    void theRawProtocolEnumCannotBeUsedAsARollupDimensionType() {
        final String rawType = FlowsSchema.createFlowsTable("riptide", 30)
                .replaceAll("(?s).*flowProtocol (Enum8\\([^)]*\\)).*", "$1")
                .replaceAll("\\s+", " ");

        assertThat(rawType).as("read off the shipped flows table, not restated here").startsWith("Enum8(");

        assertThatThrownBy(() -> FlowsSchema.reservedValueFor(rawType))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserves 'NetflowV5'")
                .hasMessageContaining("no boundary");
    }

    /**
     * The source enum must never declare {@code ''}, or the rollup dimension emits its own reserved
     * value for live rows.
     *
     * <p>{@code flowProtocol} is carried as {@code toString(f.flowProtocol)} into a
     * {@code LowCardinality(String)} whose reserved value is {@code ''}. That boundary holds only
     * because no member of the source enum stringifies to {@code ''} — an assumption the dimension's
     * javadoc states as fact and which nothing enforced. It is a live hazard rather than a
     * theoretical one: the refusal message for an enum-typed dimension suggests adding an {@code ''}
     * member, and applied to the source column instead of a rollup column that is precisely the
     * change that breaks this.</p>
     *
     * <p>The failure would be silent and unrecoverable. Real rows would land on {@code ''}, becoming
     * indistinguishable from rows aggregated before the append, so
     * {@code WHERE flowProtocol != ''} would drop live traffic for the rollup's 365-day retention
     * with nothing marking it. The regex guard in {@code appendableDimensions()} cannot see this:
     * {@code toString(f.flowProtocol)} has no fallback literal, so it is passed without inspection.</p>
     */
    @Test
    void theSourceProtocolEnumDeclaresNoEmptyMemberForTheRollupToCollideWith() {
        final String rawType = FlowsSchema.createFlowsTable("riptide", 30)
                .replaceAll("(?s).*flowProtocol (Enum8\\([^)]*\\)).*", "$1")
                .replaceAll("\\s+", " ");

        assertThat(rawType)
                .as("every member of the source enum must stringify to something the rollup's"
                        + " reserved '' cannot be confused with")
                .startsWith("Enum8(")
                .doesNotContain("''");
    }

    /**
     * The rate sits beyond the frozen primary key on every rollup, which is the only region
     * {@code ALTER … MODIFY ORDER BY} can reach: a sorting key may only grow, and only by columns
     * the same statement adds. A dimension inserted mid-list works on a fresh install and is
     * impossible on an upgraded one.
     *
     * <p>Deliberately <em>not</em> "is the last column". The rule is append-only, and the next
     * dimension to be appended will correctly go after this one — a test pinning the rate to the end
     * forever would fail that legitimate change, with a message about sampling rates rather than
     * about the rule it was really enforcing.</p>
     */
    @Test
    void everyRollupSortsByTheFrozenKeyThenExactlyTheDeclaredAppendOrder() {
        // The append log. Freezing the primary key moved the constraint off the region a NEW
        // dimension actually lands in: everything past the frozen prefix became unpinned, so a
        // dimension inserted between dstCountry and samplingInterval passed the build while being
        // impossible to reach on an upgraded deployment (planRollupRepair refuses it, ClickHouse
        // cannot MODIFY ORDER BY into the middle of a key). evolve-rollup-shape requires the BUILD
        // to catch that, not the field.
        //
        // Appending a dimension means adding it to the END of a list here. Inserting it anywhere
        // else fails, which is the whole point.
        final Map<String, List<String>> appendedAfterTheFreeze = Map.of(
                "flows_by_application_1m", List.of("samplingInterval", "flowProtocol"),
                "flows_by_conversation_1m", List.of("samplingInterval", "flowProtocol"),
                "flows_by_exporter_iface_1m", List.of("samplingInterval", "flowProtocol"),
                "flows_by_geo_asn_1m", List.of("samplingInterval", "flowProtocol"));

        FlowsSchema.rollupSortKeys().forEach((rollup, key) -> {
            final String frozen = frozenPrimaryKeyOf(rollup);
            assertThat(key).as("%s must still sort by its frozen key first", rollup).startsWith(frozen);
            final List<String> appended = Arrays.stream(key.substring(frozen.length()).split(","))
                    .map(String::trim)
                    .filter(column -> !column.isEmpty())
                    .toList();
            assertThat(appended)
                    .as("%s: a dimension appended after the freeze must go last and be declared"
                            + " here; anything else is unreachable on an upgraded deployment", rollup)
                    .isEqualTo(appendedAfterTheFreeze.get(rollup));
        });
    }

    /** The frozen literals, shared with the primary-key pin above. */
    private static String frozenPrimaryKeyOf(final String rollup) {
        for (final String ddl : FlowsSchema.createRollupTables("riptide")) {
            if (ddl.contains("`riptide`." + rollup + " (")) {
                return keyList(ddl, "PRIMARY KEY (");
            }
        }
        throw new IllegalArgumentException("no DDL for " + rollup);
    }

    @Test
    void theSamplingRateIsAppendedBeyondTheFrozenPrimaryKey() {
        assertThat(FlowsSchema.rollupSortKeys())
                .hasSize(4)
                .allSatisfy((rollup, key) -> assertThat(key).contains("samplingInterval"));

        for (final String ddl : FlowsSchema.createRollupTables("riptide")) {
            final String primary = keyList(ddl, "PRIMARY KEY (");
            final String sorting = keyList(ddl, "ORDER BY (");
            assertThat(sorting)
                    .as("the sorting key still opens with the whole frozen primary key")
                    .startsWith(primary + ", ");
            assertThat(sorting.substring(primary.length()))
                    .as("and the rate lives in the appended region, past the freeze")
                    .contains("samplingInterval");
        }
    }

    /**
     * The freeze, stated as an invariant rather than left to the literals above.
     *
     * <p>Appending the rate grew every sorting key and left every primary key alone — which is what
     * keeps a fresh install agreeing with an upgraded one, since {@code MODIFY ORDER BY} cannot
     * touch a primary key and no {@code MODIFY PRIMARY KEY} exists.</p>
     */
    @Test
    void appendingTheRateGrewTheSortKeysAndFrozeThePrimaryKeys() {
        for (final String ddl : FlowsSchema.createRollupTables("riptide")) {
            final String primary = keyList(ddl, "PRIMARY KEY (");
            final String sorting = keyList(ddl, "ORDER BY (");
            assertThat(sorting).isNotEqualTo(primary);
            assertThat(primary).doesNotContain("samplingInterval");
        }
    }

    /**
     * The rate is carried for correctness, not offered as something to slice by — the same
     * distinction Akvorado draws with {@code ConsoleNotDimension}.
     *
     * <p>Pinned against a dimension the router <em>does</em> know, because "falls back to raw flows"
     * is also what the router does with any string it does not recognise: asserting the fallback
     * alone would pass for {@code "banana"} and would therefore say nothing about a deliberate
     * exclusion. The contrast is the assertion — a real rollup dimension routes to its rollup, and
     * the rate does not, on the same call.</p>
     */
    @Test
    void theRateIsNotAGroupableRollupDimension() {
        assertThat(QueryRouter.resolveTopTalkersTable("riptide", 1440, "application"))
                .as("a genuine rollup dimension routes to its rollup, so the fallback below means"
                        + " something")
                .isNotEqualTo(FlowsSchema.qualifiedFlows("riptide"));

        assertThat(QueryRouter.resolveTopTalkersTable("riptide", 1440, "samplingInterval"))
                .isEqualTo(FlowsSchema.qualifiedFlows("riptide"));
    }

    /**
     * The repair puts an appended dimension where a fresh table puts it, not merely somewhere.
     *
     * <p>{@code ADD COLUMN} without {@code AFTER} appends past the measures, so an upgraded target
     * ends up with a different physical column order than a fresh one. Riptide does not care — a
     * materialized view with {@code TO} matches by name — but {@code INSERT INTO … SELECT} without a
     * column list is positional, and that is the backfill the ClickHouse guide tells operators to
     * write. On an upgraded target it lands the rate in {@code bytes}, shifts every measure by one,
     * and leaves {@code samplingInterval} at its type default: the reserved sentinel, so
     * {@code WHERE samplingInterval > 0} then hides the corruption it just caused.
     *
     * <p>The {@code flows} table already guarantees this (see {@code addAdditiveColumns}); the
     * rollups must too.
     */
    @Test
    void theRepairPositionsAnAppendedDimensionWhereAFreshTablePutsIt() {
        final Map<String, String> alters = FlowsSchema.alterRollupTargets("riptide");

        for (final String ddl : FlowsSchema.createRollupTables("riptide")) {
            final int from = ddl.indexOf("CREATE TABLE IF NOT EXISTS ") + "CREATE TABLE IF NOT EXISTS ".length();
            final String rollup = ddl.substring(from, ddl.indexOf(" (", from)).replace("`riptide`.", "").trim();
            final List<String> freshOrder = Arrays.stream(keyList(ddl, "ORDER BY (").split(",\\s*")).toList();

            final String alter = alters.get(rollup);
            for (int i = 1; i < freshOrder.size(); i++) {
                assertThat(alter)
                        .as("%s: %s must be added after %s, or an upgraded target's column order"
                                + " diverges from a fresh one and a positional backfill corrupts it",
                                rollup, freshOrder.get(i), freshOrder.get(i - 1))
                        .contains("ADD COLUMN IF NOT EXISTS " + freshOrder.get(i))
                        .containsPattern("ADD COLUMN IF NOT EXISTS " + freshOrder.get(i)
                                + "[^,]* AFTER " + freshOrder.get(i - 1) + ",");
            }
        }
    }
}
