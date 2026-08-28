/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.schema;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.ServerException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.riptide.e2e.ContainerImages;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a row aggregated before an appended column actually reads, asked of a real server (#629).
 *
 * <p>An appended rollup dimension's boundary rests on two separate facts. <b>A:</b> a pre-append row
 * reads {@link FlowsSchema#reservedValueFor}'s value for its type — a claim about ClickHouse, which
 * only a server can settle. <b>B:</b> no live row's expression can evaluate to that value — a claim
 * about the parsers and the wire data, settled by an argument about the expression's range. #628
 * built the enforcement for B. This is A.</p>
 *
 * <p>{@code FlowsSchemaTest} pins the mapping against hardcoded expectations, which fixes it to what
 * someone believed ClickHouse does. That is worth having and it is not this: during #606 the enum
 * rule was assumed to be first-declared-member and turned out, on a real 26.7 server, to be the
 * <b>smallest-numbered</b> member. The distinction happens not to matter for {@code flowProtocol},
 * where {@code NetflowV5 = 1} is first and smallest at once, and would have mattered silently for
 * the next enum appended. That check was manual and one-off; this repeats it.</p>
 *
 * <p><b>What this is not.</b> It is a regression test against a pinned server version, not a proof.
 * It says the mapping matches the ClickHouse it runs against, which is what makes a version bump
 * surface a changed default instead of a rollup absorbing it. It also does not cover B — that is
 * {@code SamplingIntervalBoundaryTest} and the enum arm of {@code FlowsSchemaTest}, and #607
 * proposed exactly this test as a replacement for those, which was the wrong half.</p>
 *
 * <p>The section at the end asks a second question of the same server, for #581: whether the
 * {@code SummingMergeTree} a rollup uses can carry a provenance bitmask as a <em>summary</em>, OR-ed
 * on merge rather than summed. It shares the container and nothing else.</p>
 */
@Testcontainers
public class ReservedValueIT {

    private static final String DATABASE = "reserved";

    /**
     * Distinct column types across every rollup, asserted so the derivation below cannot quietly
     * empty out. A loop over a shrunk set passes without asserting anything, which is the failure
     * mode this repo keeps meeting: {@code String}, {@code DateTime('UTC')},
     * {@code LowCardinality(String)}, {@code Float64}, {@code UInt8}, {@code IPv6}, {@code UInt32}
     * and {@code UInt64}. Adding a dimension of a new type is meant to fail here first.
     */
    private static final int LIVE_TYPES = 8;

    /** Each probe needs a table of its own; JUnit promises no order and these all mutate DDL. */
    private static final AtomicInteger PROBE = new AtomicInteger();

    @Container
    private static final GenericContainer<?> CLICKHOUSE = new GenericContainer<>(ContainerImages.clickhouse())
            .withEnv("CLICKHOUSE_USER", "riptide")
            .withEnv("CLICKHOUSE_PASSWORD", "riptide")
            .withExposedPorts(8123)
            .waitingFor(Wait.forHttp("/ping").forPort(8123).forStatusCode(200));

    private static Client admin;

    /** The server that answered, read from it rather than from the image tag. */
    private static String serverVersion;

    @BeforeAll
    static void bootstrap() throws Exception {
        admin = new Client.Builder()
                .addEndpoint("http://" + CLICKHOUSE.getHost() + ":" + CLICKHOUSE.getMappedPort(8123))
                .setUsername("riptide").setPassword("riptide").setDefaultDatabase("default").build();
        admin.execute(FlowsSchema.createDatabase(DATABASE)).get();
        admin.execute(FlowsSchema.createDatabase(SUMMARY_DATABASE)).get();
        try (var records = admin.queryRecords("SELECT version() AS v").get()) {
            for (final var record : records) {
                serverVersion = record.getString("v");
            }
        }
        assertThat(serverVersion)
                .as("the #581 probes name the server version in every answer they record, so it"
                        + " must be read rather than left null")
                .isNotBlank();
    }

    /**
     * Every type the rollups actually carry reads back what {@code reservedValueFor} claims.
     *
     * <p>The type set is derived from {@link FlowsSchema#rollupColumns()} rather than listed, so a
     * dimension added in a type nobody has probed fails here rather than at the next append.</p>
     */
    @Test
    void everyLiveColumnTypeReadsItsReservedValue() throws Exception {
        final Set<String> types = new TreeSet<>();
        FlowsSchema.rollupColumns().values().forEach(columns -> types.addAll(columns.values()));

        assertThat(types)
                .as("derived from the live schema; if this empties out the loop below asserts nothing"
                        + " and still passes")
                .hasSize(LIVE_TYPES)
                .contains("IPv6", "DateTime('UTC')", "LowCardinality(String)", "Float64", "UInt64");

        for (final String type : types) {
            final String reserved = FlowsSchema.reservedValueFor(type);
            final String table = appendColumnOfType(type);
            assertThat(preAppendRowMatches(table, type, reserved))
                    .as("a row aggregated before an appended %s column should read %s on this"
                            + " server, the boundary every appended dimension of that type rests"
                            + " on, but reads %s", type, reserved, preAppendRowValueOrUnrenderable(table))
                    .isTrue();
        }
    }

    /**
     * A wrapper decides the reserved value on its own, without consulting the type it wraps.
     *
     * <p>No dimension is wrapped today, so a probe over the live set would assert over nothing. These
     * are the literals {@code reservedValueFor}'s wrapper branches carry as verified-on-26.7 comments
     * and which no repeating check has re-asked since. Falling through to the inner type would
     * publish a value the column cannot hold, which is the exact failure that method exists to
     * prevent.</p>
     */
    @Test
    void aWrapperDecidesTheReservedValueWithoutConsultingItsInnerType() throws Exception {
        for (final String type : List.of("Nullable(String)", "Nullable(UInt8)",
                "Nullable(Enum8('' = 0, 'X' = 5))", "Array(String)", "Array(UInt64)")) {
            final String reserved = FlowsSchema.reservedValueFor(type);
            final String table = appendColumnOfType(type);
            assertThat(preAppendRowMatches(table, type, reserved))
                    .as("an appended %s should read %s, decided by the wrapper and not by what it"
                            + " wraps, but reads %s", type, reserved,
                            preAppendRowValueOrUnrenderable(table))
                    .isTrue();
        }
    }

    /**
     * An enum reads its smallest-numbered member, not its first-declared and not its zero member.
     *
     * <p>Asserted against the server's value rather than through {@code reservedValueFor}, because
     * that method <em>refuses</em> both of these: neither reserves {@code ''} as its smallest member,
     * so neither has a usable boundary. The refusal is correct only if this is what the server does,
     * and that is what this pins. {@code FlowsSchemaTest.anEnumReservesItsSmallestMemberNotItsFirstAndNotItsZero}
     * pins the rule; this pins that the rule matches ClickHouse.</p>
     *
     * <p>Both cases are built to separate the candidate rules. Riptide's own enum cannot:
     * {@code NetflowV5 = 1} is the first declared, the smallest, and the only one adjacent to zero
     * all at once, so a probe over {@code flowProtocol} would pass under any of the three.</p>
     */
    @Test
    void anEnumReadsItsSmallestMemberNotItsFirstAndNotItsZero() throws Exception {
        assertThat(preAppendRowValue(appendColumnOfType("Enum8('B' = 2, 'A' = 1)")))
                .as("the smallest member, not the first declared")
                .isEqualTo("A");
        assertThat(preAppendRowValue(appendColumnOfType("Enum8('N' = -1, '' = 0, 'P' = 1)")))
                .as("the smallest member, not the member at zero")
                .isEqualTo("N");
    }

    /**
     * Creates a one-row table and appends a column of {@code type} to its sorting key, the way
     * {@code FlowsSchema.alterRollupTargets} does it, returning the table.
     *
     * <p>One statement, not two. ClickHouse rejects a sorting-key expression naming a column an
     * earlier statement added, and rejects a newly added sort-key column that carries a
     * {@code DEFAULT} — which is precisely why the implicit type default is the only boundary
     * available and why this test exists.</p>
     *
     * <p><b>{@code allow_nullable_key} only where a Nullable is being probed.</b> Verified on 26.7:
     * without it the {@code ALTER} is refused with {@code Sorting key contains nullable columns, but
     * merge tree setting allow_nullable_key is disabled}. No rollup DDL sets it, so the
     * {@code Nullable} branch of {@code reservedValueFor} describes a column the production append
     * path could not add today — the probe still asks, because the branch exists and the answer
     * should be recorded rather than assumed. Every other probe is created exactly as a rollup
     * target is, because a probe carrying a setting the real tables lack is answering about a table
     * nobody has.</p>
     */
    private static String appendColumnOfType(final String type) throws Exception {
        final String table = DATABASE + ".probe_" + PROBE.incrementAndGet();
        admin.execute("CREATE TABLE " + table + " (k UInt64, bytes UInt64)"
                + " ENGINE = SummingMergeTree ORDER BY (k)"
                + (type.startsWith("Nullable(") ? " SETTINGS allow_nullable_key = 1" : "")).get();
        admin.execute("INSERT INTO " + table + " (k, bytes) VALUES (1, 1)").get();
        admin.execute("ALTER TABLE " + table
                + " ADD COLUMN appended " + type + " AFTER k,"
                + " MODIFY ORDER BY (k, appended)").get();
        return table;
    }

    /**
     * Whether the pre-append row's value is the reserved literal, decided by the server.
     *
     * <p>A reserved value of the wrong <em>family</em> never reaches the comparison: the server
     * rejects {@code IPv6 = 0} outright with {@code ILLEGAL_TYPE_OF_ARGUMENT}. That is still a
     * detection, but as a bare stack trace it names the SQL rather than the mapping that produced
     * it, so it is converted into the failure a reader can act on.</p>
     */
    private static boolean preAppendRowMatches(final String table, final String type, final String reserved)
            throws Exception {
        // NULL never equals anything, including itself, so the reserved literal cannot be compared
        // with = in that one case without silently reporting a mismatch for a correct mapping
        final String predicate = "NULL".equals(reserved) ? "appended IS NULL" : "appended = " + reserved;
        try (var records = admin.queryRecords(
                "SELECT count() AS c FROM " + table + " WHERE " + predicate).get()) {
            for (final var record : records) {
                return record.getLong("c") == 1;
            }
        } catch (final ServerException notComparable) {
            throw new AssertionError("reserved value " + reserved + " for type " + type
                    + " is not comparable with a column of that type, so it cannot be the value a"
                    + " pre-append row holds: " + notComparable.getMessage(), notComparable);
        }
        return false;
    }

    /**
     * The pre-append row's value rendered for a failure message, never throwing.
     *
     * <p>The comparison above reduces the server's answer to a boolean, so a failure would otherwise
     * print only the literal this project <em>expects</em>, phrased as though it were what the server
     * said. That is exactly backwards for the case this test exists to catch: a ClickHouse release
     * that changes a default produces a red test naming the old value and never the new one, so
     * diagnosing it means reproducing the probe by hand.</p>
     *
     * <p>Rendering is best-effort on purpose. It runs on the passing path too (AssertJ evaluates a
     * description eagerly), and a type this client cannot render must not turn a green probe red or
     * borrow the "not comparable" message, which would name the wrong cause.</p>
     */
    private static String preAppendRowValueOrUnrenderable(final String table) {
        try {
            final String value = preAppendRowValue(table);
            return value == null ? "NULL" : "'" + value + "'";
        } catch (final Exception unrenderable) {
            return "a value this client could not render (" + unrenderable.getMessage() + ")";
        }
    }

    /** The pre-append row's value rendered as text, for the enum cases the mapping refuses. */
    private static String preAppendRowValue(final String table) throws Exception {
        try (var records = admin.queryRecords("SELECT toString(appended) AS v FROM " + table).get()) {
            for (final var record : records) {
                return record.getString("v");
            }
        }
        return null;
    }

    // ---- #581 probe: can a rollup carry a provenance summary? ---------------------------------
    //
    // What this asks and what it does not. It asks the ENGINE: what SummingMergeTree does to a
    // provenance mask when it merges two parts, and what a read over unmerged parts returns. It
    // asks with one-key tables of its own, not a rollup: no materialized view feeds them, so
    // whether `CREATE MATERIALIZED VIEW ... TO` coerces a plain integer expression into a
    // SimpleAggregateFunction column is not asked here. #581's checkbox "probed against a real
    // SummingMergeTree rollup" is answered for the engine half only.
    //
    // UInt64, not the UInt8 #581 proposes: FlowsSchema hardcodes UInt64 for every measure, at the
    // rollup DDL and again in rollupColumns(), so a mask added as a measure would be that width.

    /** Its own database, so nothing here disturbs the reserved-value fixture above. */
    private static final String SUMMARY_DATABASE = "provenance_probe";

    /**
     * Two masks whose bits <em>overlap</em>: {@code 0b011} and {@code 0b110}.
     *
     * <p>Overlapping on purpose. A sum and a bitwise OR agree on disjoint bits — {@code 1 + 2} and
     * {@code 1 | 2} are both {@code 3} — so a probe built on disjoint masks passes whichever
     * aggregation the engine applies, and would have called a silently wrong column correct. And
     * neither mask is a subset of the other, so the OR ({@code 0b111}) equals neither input: a read
     * that returned one unmerged row instead of the merge would not pass for the OR either.
     * {@link #twoMasksMustDisagreeUnderSumAndOr()} pins that the fixture kept both properties.</p>
     */
    private static final long MASK_A = 0b011;
    private static final long MASK_B = 0b110;

    /** What a bitwise OR of the two masks is: the value a provenance summary must read. */
    private static final long ORED = MASK_A | MASK_B;

    /** What summing them gives instead: what a plain measure column would silently record. */
    private static final long SUMMED = MASK_A + MASK_B;

    /** The {@code bytes} each probe row carries beside its mask; the two rows must sum to this. */
    private static final long BYTES_PER_ROW = 1;

    /** The single row of {@code table} after the merge: the mask and the summed measure beside it. */
    private record Merged(long mask, long bytes) { }

    /** How many active parts {@code database.table} holds right now. */
    private static long activeParts(final String table) throws Exception {
        final String[] name = table.split("\\.", 2);
        try (var records = admin.queryRecords("SELECT count() AS c FROM system.parts WHERE database = '"
                + name[0] + "' AND table = '" + name[1] + "' AND active").get()) {
            for (final var record : records) {
                return record.getLong("c");
            }
        }
        throw new AssertionError("system.parts answered nothing for " + table);
    }

    /**
     * Merges {@code table} explicitly and returns its single row.
     *
     * <p>Asserted on both sides of the merge. Before: two active parts, or the merge measures
     * nothing. After: one row, because a read over two unmerged rows returns whichever comes first,
     * and a value read that way says nothing about what the engine did.</p>
     */
    private static Merged merged(final String table) throws Exception {
        assertThat(activeParts(table))
                .as("%s must hold two unmerged parts before the merge, or nothing is measured", table)
                .isEqualTo(2);
        admin.execute("SYSTEM START MERGES " + table).get();
        admin.execute("OPTIMIZE TABLE " + table + " FINAL").get();

        try (var records = admin.queryRecords(
                "SELECT count() AS rows, any(mask) AS mask, any(bytes) AS bytes FROM " + table).get()) {
            for (final var record : records) {
                assertThat(record.getLong("rows"))
                        .as("%s must hold exactly one row after OPTIMIZE FINAL; a first-of-many read"
                                + " would not be the merged value", table)
                        .isEqualTo(1);
                return new Merged(record.getLong("mask"), record.getLong("bytes"));
            }
        }
        throw new AssertionError(table + " held no row after the merge, so nothing was measured");
    }

    /**
     * A table of {@code maskType} beside a plain {@code bytes} measure, the shape #581 would ship,
     * fed the two overlapping masks as two parts that stay unmerged until a probe asks otherwise.
     */
    private static String tableCarrying(final String maskType) throws Exception {
        final String table = SUMMARY_DATABASE + ".probe_" + PROBE.incrementAndGet();
        admin.execute("CREATE TABLE " + table + " (k String, mask " + maskType + ", bytes UInt64)"
                + " ENGINE = SummingMergeTree ORDER BY k").get();
        // Background merges would otherwise collapse the two parts at a moment of their choosing,
        // and a probe that asks about unmerged parts must be sure they are.
        admin.execute("SYSTEM STOP MERGES " + table).get();
        // Separate INSERTs on purpose: optimize_on_insert (default 1) merges the rows of a single
        // statement before the part is written, and the question is what the ENGINE does to two
        // parts, which is what a rollup accumulates.
        admin.execute("INSERT INTO " + table + " VALUES ('k', " + MASK_A + ", " + BYTES_PER_ROW + ")").get();
        admin.execute("INSERT INTO " + table + " VALUES ('k', " + MASK_B + ", " + BYTES_PER_ROW + ")").get();
        return table;
    }

    /**
     * The fixture's masks must disagree under sum and OR, or every assertion below is vacuous.
     *
     * <p>This is the guard that matters here. On disjoint bits the two aggregations coincide, so a
     * probe using them would pass with a plain {@code UInt64} column that silently sums a bitmask —
     * the exact defect the other tests exist to catch. And the OR must equal neither input, or a
     * read that never saw a merge could still return it.</p>
     */
    @Test
    void twoMasksMustDisagreeUnderSumAndOr() {
        assertThat(SUMMED)
                .as("the probe masks must overlap: with disjoint bits a sum and an OR agree, and"
                        + " every assertion in this section would hold for the wrong column type")
                .isNotEqualTo(ORED);
        assertThat(ORED)
                .as("the OR must equal neither mask, or one unmerged row read first would pass as"
                        + " the merged value")
                .isNotEqualTo(MASK_A)
                .isNotEqualTo(MASK_B);
    }

    /**
     * A plain measure column sums a bitmask rather than OR-ing it, and says nothing about it (#581).
     *
     * <p>#581 wants sampling provenance carried into the rollups as a summary. Riptide's measures are
     * all plain {@code UInt64} — {@code FlowsSchema} hardcodes the type at the rollup DDL and again
     * in {@code rollupColumns()}, and its {@code Measure} record carries no type at all. So a
     * provenance mask added the way every existing measure is added would be <em>summed</em> on
     * merge. This asks the server what that actually produces.</p>
     */
    @Test
    void aPlainMeasureColumnSumsAProvenanceMaskInsteadOfOringIt() throws Exception {
        final Merged merged = merged(tableCarrying("UInt64"));

        assertThat(merged.mask())
                .as("#581 [PQ-4] on ClickHouse %s: a plain UInt64 measure SUMS a bitmask — %d and %d"
                        + " merge to %d, not the %d a provenance summary means",
                        serverVersion, MASK_A, MASK_B, merged.mask(), ORED)
                .isEqualTo(SUMMED)
                .isNotEqualTo(ORED);
    }

    /**
     * A {@code SimpleAggregateFunction(groupBitOr)} column ORs it, inside the same engine, beside a
     * measure that still sums (#581).
     *
     * <p>The answer #581 needs: {@code SummingMergeTree} honours a {@code SimpleAggregateFunction}
     * column's own function rather than summing it, while the plain {@code bytes} beside it is
     * summed as before. So a provenance summary is expressible in the rollup engine riptide already
     * uses, in the shape a rollup has — but only at that type, never as a plain measure.</p>
     */
    @Test
    void aSimpleAggregateFunctionColumnOrsTheProvenanceMask() throws Exception {
        final Merged merged = merged(tableCarrying("SimpleAggregateFunction(groupBitOr, UInt64)"));

        assertThat(merged.mask())
                .as("#581 [PQ-4] on ClickHouse %s: SimpleAggregateFunction(groupBitOr, UInt64) inside"
                        + " a SummingMergeTree ORs — %d and %d merge to %d", serverVersion,
                        MASK_A, MASK_B, merged.mask(), ORED)
                .isEqualTo(ORED);
        assertThat(merged.bytes())
                .as("#581 [PQ-4] on ClickHouse %s: the plain measure beside the summary is still"
                        + " summed in the same merge", serverVersion)
                .isEqualTo(2 * BYTES_PER_ROW);
    }

    /**
     * Over unmerged parts, the read is right only when the query names the column's function (#581).
     *
     * <p>A rollup is read between background merges, so a query sees parts the engine has not yet
     * collapsed. Read with {@code groupBitOr} the summary is OR-ed across them. Read with
     * {@code sum}, the way every rollup measure is read today, the same column is <em>summed</em>:
     * the column type does not protect a reader that picks the wrong function. That is a limit
     * #581 must carry to every consumer of the column, not a property of the engine.</p>
     */
    @Test
    void theProvenanceSummaryReadsOredOverUnmergedPartsOnlyUnderItsOwnFunction() throws Exception {
        final String table = tableCarrying("SimpleAggregateFunction(groupBitOr, UInt64)");
        assertThat(activeParts(table))
                .as("%s must still hold two unmerged parts, or this read measures a merge", table)
                .isEqualTo(2);

        try (var records = admin.queryRecords("SELECT count() AS rows, groupBitOr(mask) AS ored,"
                + " sum(mask) AS summed FROM " + table + " GROUP BY k").get()) {
            for (final var record : records) {
                assertThat(record.getLong("rows"))
                        .as("the GROUP BY must have spanned both unmerged rows")
                        .isEqualTo(2);
                assertThat(record.getLong("ored"))
                        .as("#581 [PQ-4] on ClickHouse %s: groupBitOr over unmerged parts reads the"
                                + " summary OR-ed", serverVersion)
                        .isEqualTo(ORED);
                assertThat(record.getLong("summed"))
                        .as("#581 [PQ-4] on ClickHouse %s: sum() over the same unmerged parts, the"
                                + " read every rollup measure gets today, gives %d and not %d",
                                serverVersion, SUMMED, ORED)
                        .isEqualTo(SUMMED);
                return;
            }
        }
        throw new AssertionError("the GROUP BY returned no row, so nothing was measured");
    }
}
