/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.schema;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rollup shape drift (#470).
 *
 * <p>The live state is passed in as data rather than read from a server, which is what makes the
 * two cases that matter testable here at all: a view whose expression changed while every column
 * name stayed the same, and a role that cannot see the view.</p>
 */
class RollupShapeCheckTest {

    private static final String DB = "riptide";

    /**
     * What ClickHouse 26.7 actually stored in {@code system.tables.as_select}, captured verbatim
     * after applying riptide's own emitted DDL.
     *
     * <p>This literal is the only thing in the suite that is not derived from the implementation's
     * own assumption about how the server reformats SQL. The fixture below simulates the server by
     * applying the same transformation {@link RollupShapeCheck#normalise} does, which makes it
     * useless as evidence that the transformation is the right one — it would agree with a wrong
     * assumption just as readily. Pinning one real response closes that gap: if a server version
     * ever re-serialises differently, this fails rather than the check silently reporting every
     * deployment as stale.</p>
     */
    private static final String AS_SELECT_FROM_CLICKHOUSE_26_7 =
            "SELECT f.tenant AS tenant, f.organisation AS organisation, "
                    + "toStartOfMinute(f.timestamp) AS timestamp, f.zone AS zone, "
                    + "ifNull(f.application, '') AS application, f.protocol AS protocol, "
                    + "f.samplingInterval AS samplingInterval, "
                    + "toString(f.flowProtocol) AS flowProtocol, "
                    + "sum(f.bytes) AS bytes, sum(f.packets) AS packets, count() AS flowCount, "
                    + "sumIf(f.bytes, f.direction = 'INGRESS') AS bytesIn, "
                    + "sumIf(f.bytes, f.direction = 'EGRESS') AS bytesOut, "
                    + "sumIf(f.packets, f.direction = 'INGRESS') AS packetsIn, "
                    + "sumIf(f.packets, f.direction = 'EGRESS') AS packetsOut "
                    + "FROM riptide.flows AS f "
                    + "GROUP BY tenant, organisation, timestamp, zone, application, protocol,"
                    + " samplingInterval, flowProtocol";

    /**
     * A real server response matches the SELECT riptide emits for that rollup.
     *
     * <p>Note it does NOT match literally — the server strips the backticks around the database
     * name and puts the whole statement on one line. That is the entire reason
     * {@link RollupShapeCheck#normalise} exists, and asserting both halves here is what stops
     * someone "simplifying" it to {@code equals}.</p>
     */
    @Test
    void aRealServerResponseMatchesTheEmittedSelect() {
        final String emitted = FlowsSchema.rollupSelects(DB).get(FlowsSchema.ROLLUP_BY_APPLICATION);

        assertThat(emitted)
                .as("if these were literally equal, no normalisation would be needed")
                .isNotEqualTo(AS_SELECT_FROM_CLICKHOUSE_26_7);
        assertThat(RollupShapeCheck.normalise(emitted))
                .isEqualTo(RollupShapeCheck.normalise(AS_SELECT_FROM_CLICKHOUSE_26_7));

        final Map<String, String> live = liveSelects();
        live.put(FlowsSchema.ROLLUP_BY_APPLICATION + "_mv", AS_SELECT_FROM_CLICKHOUSE_26_7);
        assertThat(RollupShapeCheck.compare(DB, live, liveColumns(), liveSortKeys()))
                .allSatisfy(r -> assertThat(r.status()).isEqualTo(RollupShapeCheck.Status.MATCHES));
    }

    /** The state a correctly provisioned, current-version deployment presents. */
    private static Map<String, String> liveSelects() {
        final Map<String, String> selects = new LinkedHashMap<>();
        FlowsSchema.rollupSelects(DB).forEach((table, select) ->
                // as ClickHouse returns it: one line, no backticks
                selects.put(table + "_mv", select.replace("`", "").replaceAll("\\s+", " ")));
        return selects;
    }

    /** Every rollup's sorting key as this version writes it — the healthy case. */
    private static Map<String, String> liveSortKeys() {
        return new LinkedHashMap<>(FlowsSchema.rollupSortKeys());
    }

    private static Map<String, Map<String, String>> liveColumns() {
        final Map<String, Map<String, String>> columns = new LinkedHashMap<>();
        FlowsSchema.rollupColumns().forEach((table, types) -> columns.put(table, new LinkedHashMap<>(types)));
        return columns;
    }

    @Test
    void aCurrentDeploymentIsSilent() {
        final List<RollupShapeCheck.Result> results =
                RollupShapeCheck.compare(DB, liveSelects(), liveColumns(), liveSortKeys());

        assertThat(results).hasSize(4)
                .allSatisfy(r -> assertThat(r.status()).isEqualTo(RollupShapeCheck.Status.MATCHES));
    }

    /**
     * The reason the SELECT comparison exists. A corrected aggregate keeps its column name, so the
     * column-set comparison sees nothing at all.
     */
    @Test
    void aChangedExpressionUnderIdenticalColumnNamesIsDrift() {
        final Map<String, String> live = liveSelects();
        final String mv = FlowsSchema.ROLLUP_BY_APPLICATION + "_mv";
        live.put(mv, live.get(mv).replace("sumIf(f.packets, f.direction = 'EGRESS') AS packetsOut",
                "sum(f.packets) AS packetsOut"));

        final List<RollupShapeCheck.Result> results = RollupShapeCheck.compare(DB, live, liveColumns(), liveSortKeys());

        assertThat(results)
                .filteredOn(RollupShapeCheck.Result::drifted)
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.rollup()).isEqualTo(FlowsSchema.ROLLUP_BY_APPLICATION);
                    assertThat(r.detail()).contains(mv);
                });
        // one stale rollup must not describe the other three
        assertThat(results).filteredOn(r -> r.status() == RollupShapeCheck.Status.MATCHES).hasSize(3);
    }

    /** Formatting is the server's, not the query's: the same SQL laid out differently is a match. */
    @Test
    void serverReformattingIsNotDrift() {
        final Map<String, String> live = new HashMap<>();
        FlowsSchema.rollupSelects(DB).forEach((table, select) -> live.put(table + "_mv",
                "\n  " + select.replace("`", "").replaceAll("\\s+", "\n\t") + "  \n"));

        assertThat(RollupShapeCheck.compare(DB, live, liveColumns(), liveSortKeys()))
                .allSatisfy(r -> assertThat(r.status()).isEqualTo(RollupShapeCheck.Status.MATCHES));
    }

    @Test
    void aMissingTargetColumnIsDriftAndIsNamed() {
        final Map<String, Map<String, String>> columns = liveColumns();
        columns.get(FlowsSchema.ROLLUP_BY_GEO_ASN).remove("dstCountry");

        assertThat(RollupShapeCheck.compare(DB, liveSelects(), columns, liveSortKeys()))
                .filteredOn(RollupShapeCheck.Result::drifted)
                .singleElement()
                .satisfies(r -> assertThat(r.detail()).contains("missing").contains("dstCountry"));
    }

    /** Missing and unexpected mean different things, so they are reported apart. */
    @Test
    void anUnexpectedTargetColumnIsReportedSeparatelyFromAMissingOne() {
        final Map<String, Map<String, String>> columns = liveColumns();
        columns.get(FlowsSchema.ROLLUP_BY_CONVERSATION).put("srcCity", "LowCardinality(String)");

        assertThat(RollupShapeCheck.compare(DB, liveSelects(), columns, liveSortKeys()))
                .filteredOn(RollupShapeCheck.Result::drifted)
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.detail()).contains("unexpected").contains("srcCity");
                    assertThat(r.detail()).doesNotContain("missing");
                });
    }

    /**
     * The grant problem. ClickHouse filters {@code system.tables} by access silently, so an
     * ungranted view is zero rows — exactly what an absent view looks like. Calling that "stale"
     * would warn on every deployment provisioned before the grant existed, and an operator who
     * learns to ignore the warning is worse off than one who never had it.
     *
     * <p>A visible target does <em>not</em> settle it, which a review round argued it should:
     * riptide grants per object, so a writer holding INSERT on the target and no {@code SHOW TABLES}
     * on the {@code _mv} is an ordinary pre-#572 deployment. {@code RollupShapeDriftIT} builds
     * exactly that against a real server.</p>
     */
    @Test
    void aViewTheUserCannotSeeIsUnverifiableRatherThanStale() {
        final Map<String, String> live = liveSelects();
        live.remove(FlowsSchema.ROLLUP_BY_EXPORTER_IFACE + "_mv");

        final List<RollupShapeCheck.Result> results = RollupShapeCheck.compare(DB, live, liveColumns(), liveSortKeys());

        assertThat(results)
                .filteredOn(r -> r.status() == RollupShapeCheck.Status.UNVERIFIABLE)
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.rollup()).isEqualTo(FlowsSchema.ROLLUP_BY_EXPORTER_IFACE);
                    assertThat(r.detail()).contains("riptide onboard").contains("GRANT SHOW TABLES");
                });
        assertThat(results).noneMatch(RollupShapeCheck.Result::drifted);
    }

    /**
     * Proof of drift outranks an unreadable view. The column comparison is conclusive on its own,
     * and reporting "cannot verify" while holding it would bury the finding.
     */
    @Test
    void columnDriftIsReportedEvenWhenTheViewCannotBeRead() {
        final Map<String, String> live = liveSelects();
        live.remove(FlowsSchema.ROLLUP_BY_GEO_ASN + "_mv");
        final Map<String, Map<String, String>> columns = liveColumns();
        columns.get(FlowsSchema.ROLLUP_BY_GEO_ASN).remove("srcAs");

        assertThat(RollupShapeCheck.compare(DB, live, columns, liveSortKeys()))
                .filteredOn(r -> r.rollup().equals(FlowsSchema.ROLLUP_BY_GEO_ASN))
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.status()).isEqualTo(RollupShapeCheck.Status.DRIFTED);
                    assertThat(r.detail()).contains("srcAs");
                });
    }

    /**
     * An absent or ungranted target table is UNREACHABLE, not UNVERIFIABLE, and the difference is
     * behavioural: a query routed there fails with UNKNOWN_TABLE or ACCESS_DENIED, so the query
     * path must decline it. Leaving it routable would turn a graceful raw-flows fallback into a
     * hard error on every long-range query.
     */
    @Test
    void anInvisibleTargetTableIsUnreachableAndDeclined() {
        final Map<String, Map<String, String>> columns = liveColumns();
        columns.remove(FlowsSchema.ROLLUP_BY_APPLICATION);

        assertThat(RollupShapeCheck.compare(DB, liveSelects(), columns, liveSortKeys()))
                .filteredOn(r -> r.status() == RollupShapeCheck.Status.UNREACHABLE)
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.detail()).contains("target table");
                    assertThat(r.declineForQueries()).isTrue();
                });
    }

    /**
     * A column whose type changed keeps its name, so a name-only comparison passes it clean while
     * the column silently truncates or wraps. This is the half of #571's hazard the check can see.
     */
    @Test
    void aColumnWhoseTypeChangedIsDrift() {
        final Map<String, Map<String, String>> columns = liveColumns();
        columns.get(FlowsSchema.ROLLUP_BY_GEO_ASN).put("srcAs", "UInt32");

        assertThat(RollupShapeCheck.compare(DB, liveSelects(), columns, liveSortKeys()))
                .filteredOn(RollupShapeCheck.Result::drifted)
                .singleElement()
                .satisfies(r -> assertThat(r.detail())
                        .contains("wrong type").contains("srcAs").contains("UInt32").contains("UInt64"));
    }

    /**
     * An unverifiable rollup stays in use; only drift and unreachability decline it.
     *
     * <p>What remains genuinely unverifiable, now that an invisible view beside a visible target is
     * treated as missing: a sorting key that could not be read at all. Everything else about the
     * rollup checked out, so there is proof of nothing — and declining on no evidence is what would
     * degrade a deployment whose only fault is a grant.</p>
     */
    @Test
    void onlyDriftAndUnreachabilityDeclineARollup() {
        final Map<String, String> keys = liveSortKeys();
        keys.remove(FlowsSchema.ROLLUP_BY_EXPORTER_IFACE);

        assertThat(RollupShapeCheck.compare(DB, liveSelects(), liveColumns(), keys))
                .filteredOn(r -> r.status() == RollupShapeCheck.Status.UNVERIFIABLE)
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.rollup()).isEqualTo(FlowsSchema.ROLLUP_BY_EXPORTER_IFACE);
                    assertThat(r.declineForQueries()).isFalse();
                });
    }

    /**
     * The normalisation's blind spot, held shut.
     *
     * <p>{@link RollupShapeCheck#normalise} collapses runs of whitespace, which also collapses
     * whitespace <em>inside</em> a string literal. Two SELECTs differing only there would compare
     * equal and the drift would be missed. Nothing today can trigger it, and this test is what
     * keeps that true: adding a literal like {@code 'not set'} to a rollup expression fails here,
     * at the moment the literal is written, rather than silently widening the blind spot.</p>
     */
    @Test
    void noRollupExpressionCarriesALiteralWithInternalWhitespace() {
        final Pattern literal = Pattern.compile("'([^']*)'");
        final java.util.Set<String> offenders = FlowsSchema.rollupSelects(DB).values().stream()
                .flatMap(select -> {
                    final Matcher m = literal.matcher(select);
                    final java.util.Set<String> found = new java.util.HashSet<>();
                    while (m.find()) {
                        if (Pattern.compile("\\s").matcher(m.group(1)).find()) {
                            found.add(m.group(1));
                        }
                    }
                    return found.stream();
                })
                .collect(Collectors.toSet());

        assertThat(offenders)
                .as("a literal with internal whitespace would survive in as_select but be collapsed "
                        + "by normalise(), so drift confined to it would compare equal")
                .isEmpty();
    }

    /**
     * A target whose columns and view both compare clean is still drifted if its sorting key is not
     * this version's.
     *
     * <p>The state is reachable: an operator hand-adds the column, so it exists but sits outside the
     * key, and no ALTER can move it there (ClickHouse rejects that with Code 36). The rate is then a
     * plain numeric column of a {@code SummingMergeTree}, which means the engine <em>sums</em> it
     * across merges — {@code sum(bytes * samplingInterval)} inflated by an arbitrary factor.</p>
     *
     * <p>Checked here rather than remembered by the code that refused the repair, because a
     * validate-mode collector issues no DDL and computes no plan at all, and that is the deployment
     * shape that cannot fix itself.</p>
     */
    @Test
    void aRateOutsideTheSortingKeyIsDriftedEvenWhenEverythingElseMatches() {
        final Map<String, String> keys = liveSortKeys();
        final String rollup = FlowsSchema.rollupTableNames().getFirst();
        keys.put(rollup, keys.get(rollup).replace(", samplingInterval", ""));

        final List<RollupShapeCheck.Result> results =
                RollupShapeCheck.compare(DB, liveSelects(), liveColumns(), keys);

        assertThat(results).filteredOn(r -> r.rollup().equals(rollup))
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.drifted()).isTrue();
                    assertThat(r.declineForQueries()).isTrue();
                    assertThat(r.detail()).contains("sorting key").contains("SummingMergeTree");
                });
        assertThat(results).filteredOn(r -> !r.rollup().equals(rollup))
                .allSatisfy(r -> assertThat(r.declineForQueries())
                        .as("only the rollup with the wrong key is declined")
                        .isFalse());
    }

    /** Spacing and backticks are the server's formatting, not a different key. */
    @Test
    void aKeyDifferingOnlyInFormattingIsTheSameKey() {
        final Map<String, String> keys = liveSortKeys();
        keys.replaceAll((rollup, key) -> "`" + key.replace(", ", "`,  `") + "`");

        assertThat(RollupShapeCheck.compare(DB, liveSelects(), liveColumns(), keys))
                .allSatisfy(r -> assertThat(r.declineForQueries()).isFalse());
    }
}
