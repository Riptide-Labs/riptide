/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.schema;

import org.intellij.lang.annotations.Language;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
     * {@code CREATE MATERIALIZED VIEW IF NOT EXISTS … TO <target>} for every rollup. Must be emitted
     * <em>after</em> {@link #createRollupTables} — a view whose {@code TO} target does not yet exist
     * fails to create.
     */
    public static List<String> createRollupViews(final String database) {
        return ROLLUPS.stream().map(rollup -> rollupView(database, rollup)).toList();
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
        ddl.append("\n) ENGINE = SummingMergeTree()\nORDER BY (")
                .append(columns.stream().map(Dimension::column).collect(Collectors.joining(", ")))
                .append(")\nPARTITION BY toYYYYMM(timestamp)\n")
                .append("TTL timestamp + INTERVAL ").append(ttlDays).append(" DAY\n")
                .append("SETTINGS index_granularity = 8192");
        return ddl.toString();
    }

    /** The materialized view feeding one rollup target from {@code flows}. */
    private static String rollupView(final String database, final Rollup rollup) {
        final List<Dimension> columns = allDimensions(rollup);
        final StringBuilder ddl = new StringBuilder("CREATE MATERIALIZED VIEW IF NOT EXISTS ")
                .append(qualifiedRollupView(database, rollup.table()))
                .append(" TO ").append(qualifiedRollup(database, rollup.table()))
                .append(" AS\nSELECT\n");
        ddl.append(columns.stream()
                .map(dimension -> "    " + dimension.selectItem())
                .collect(Collectors.joining(",\n")));
        ddl.append(",\n").append(MEASURES.stream()
                .map(measure -> "    " + measure.expression() + " AS " + measure.column())
                .collect(Collectors.joining(",\n")));
        ddl.append("\nFROM ").append(qualifiedFlows(database)).append(" AS ").append(SOURCE_ALIAS)
                .append("\nGROUP BY ")
                .append(columns.stream().map(Dimension::column).collect(Collectors.joining(", ")));
        return ddl.toString();
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

    /** The 1-minute rollups. Adding one here propagates to creation, grants, and row policies. */
    private static final List<Rollup> ROLLUPS = List.of(
            new Rollup("flows_by_application_1m", List.of(
                    APPLICATION,
                    Dimension.of("protocol", "UInt8"))),
            new Rollup("flows_by_conversation_1m", List.of(
                    Dimension.of("srcAddr", "IPv6"),
                    Dimension.of("dstAddr", "IPv6"),
                    APPLICATION)),
            new Rollup("flows_by_exporter_iface_1m", List.of(
                    Dimension.of("exporterAddr", "String"),
                    Dimension.of("exporterName", "LowCardinality(String)"),
                    Dimension.of("inputSnmp", "UInt32"),
                    Dimension.of("outputSnmp", "UInt32"))),
            new Rollup("flows_by_geo_asn_1m", List.of(
                    Dimension.of("srcAs", "UInt64"),
                    Dimension.of("dstAs", "UInt64"),
                    Dimension.of("srcCountry", "LowCardinality(String)"),
                    Dimension.of("dstCountry", "LowCardinality(String)"))));

    /** One rollup: its target table name and the dimensions it adds to {@link #PREAMBLE}. */
    private record Rollup(String table, List<Dimension> dimensions) {
    }

    /**
     * A rollup column: its name and type in the target table, and the expression selecting it from
     * {@code flows} in the view. Every expression is alias-qualified so the view never depends on
     * name resolution against the source table.
     */
    private record Dimension(String column, String type, String expression) {

        /** A dimension read straight through from the identically-named source column. */
        static Dimension of(final String column, final String type) {
            return new Dimension(column, type, SOURCE_ALIAS + "." + column);
        }

        String selectItem() {
            return expression + " AS " + column;
        }
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
            exporterName LowCardinality(String)
        ) ENGINE = MergeTree()
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
            toInt64({ival:Int64} * 1e9) AS interval_ns,

            -- Find first and last absolute bucket numbers. Integer division is load-bearing:
            -- Float64 '/' cannot represent nanosecond epochs exactly (ULP ~256ns), which would
            -- absorb the boundary shift below. greatest() clamps corrupt flows with
            -- lastSwitched < deltaSwitched to one bucket — unclamped, bucket_count wraps and
            -- range() throws, poisoning every query over the view. The 1ns shift moves a flow
            -- ending exactly on a bucket boundary into the preceding bucket instead of emitting
            -- a spurious zero-contribution bucket.
            toUInt64(intDiv(toUnixTimestamp64Nano(deltaSwitched), interval_ns)) as first_bucket,
            toUInt64(intDiv(toUnixTimestamp64Nano(greatest(lastSwitched, deltaSwitched))
                            - if(age('ns', deltaSwitched, lastSwitched) > 0, 1, 0),
                            interval_ns)) as last_bucket,

            last_bucket - first_bucket + 1 as bucket_count,

            -- Calculate total duration in nanoseconds
            age('ns', deltaSwitched, lastSwitched) as flow_duration,

            -- Generate buckets from the first bucket onward
            range(toUInt32(bucket_count)) AS buckets,

            -- Determine the fraction of the interval used in each case
            CASE
                -- Only one bucket
                WHEN bucket_count = 1
                    THEN 1.0

                -- First bucket: Portion from start time to the next bucket boundary
                WHEN bucket = 0
                    THEN ((interval_ns * (first_bucket + 1)) - toUnixTimestamp64Nano(deltaSwitched)) / interval_ns

                -- Last bucket: Portion from the start of the last bucket to end time
                WHEN bucket = bucket_count - 1
                    THEN (toUnixTimestamp64Nano(lastSwitched) - (interval_ns * last_bucket)) / interval_ns

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
            flow.*,

            fromUnixTimestamp64Nano((first_bucket + bucket) * interval_ns) AS timestamp,

            bytes * bucket_share as bytes,
            packets * bucket_share as packets

        FROM @@flows@@ AS flow
        ARRAY JOIN buckets AS bucket
        ORDER BY timestamp;
    """;
}
