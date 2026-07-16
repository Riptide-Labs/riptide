/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.schema;

import org.intellij.lang.annotations.Language;

import java.util.regex.Pattern;

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
 */
public final class FlowsSchema {

    /** The collector's manage-mode retention; also the {@code onboard --ttl-days} default. */
    public static final int DEFAULT_TTL_DAYS = 30;

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

    // Placeholder tokens substituted with the qualified names / TTL. Plain replace() (not
    // String.format) avoids treating the multi-line DDL as a format string.
    private static final String FLOWS_TOKEN = "@@flows@@";
    private static final String SAMPLES_TOKEN = "@@samples@@";
    private static final String TTL_DAYS_TOKEN = "@@ttlDays@@";

    @Language("ClickHouse")
    private static final String FLOWS_TABLE = """
        CREATE TABLE IF NOT EXISTS @@flows@@ (
            timestamp DateTime64(3),

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

            receivedAt DateTime64(9),

            firstSwitched DateTime64(9),
            deltaSwitched DateTime64(9),
            lastSwitched DateTime64(9),

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

            clockCorrection Nullable(Int64)
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
