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

    @BeforeAll
    static void bootstrap() throws Exception {
        admin = new Client.Builder()
                .addEndpoint("http://" + CLICKHOUSE.getHost() + ":" + CLICKHOUSE.getMappedPort(8123))
                .setUsername("riptide").setPassword("riptide").setDefaultDatabase("default").build();
        admin.execute(FlowsSchema.createDatabase(DATABASE)).get();
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
}
