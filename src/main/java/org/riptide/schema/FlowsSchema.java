/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.schema;

import org.intellij.lang.annotations.Language;

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
 * pinned client and on an unpinned admin client. The database name is backtick-quoted; callers pass
 * a validated name.
 *
 * <p>The {@code samples} view carries no data and is created with {@code CREATE OR REPLACE}, so it
 * always tracks the running version. It is used only by the collector's manage path — {@code
 * onboard} does not create it (in provisioned mode the reader role is not granted {@code SELECT} on
 * it, so it would be inert).
 */
public final class FlowsSchema {

    private FlowsSchema() {
    }

    /** {@code CREATE DATABASE IF NOT EXISTS `<db>`} — safe on an unpinned connection. */
    public static String createDatabase(final String database) {
        return "CREATE DATABASE IF NOT EXISTS " + ident(database);
    }

    /** {@code CREATE TABLE IF NOT EXISTS `<db>`.flows (…)} — a pre-existing table is left intact. */
    public static String createFlowsTable(final String database) {
        return FLOWS_TABLE.replace(FLOWS_TOKEN, ident(database) + ".flows");
    }

    /** {@code CREATE OR REPLACE VIEW `<db>`.samples AS … FROM `<db>`.flows} — collector-only. */
    public static String createSamplesView(final String database) {
        return SAMPLES_VIEW
                .replace(SAMPLES_TOKEN, ident(database) + ".samples")
                .replace(FLOWS_TOKEN, ident(database) + ".flows");
    }

    /** Backtick-quote an identifier. The value is pre-validated to exclude backticks. */
    private static String ident(final String name) {
        return "`" + name + "`";
    }

    // Placeholder tokens substituted with the qualified names. Plain replace() (not String.format)
    // avoids treating the multi-line DDL as a format string.
    private static final String FLOWS_TOKEN = "@@flows@@";
    private static final String SAMPLES_TOKEN = "@@samples@@";

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
        TTL toDateTime(timestamp) + INTERVAL 30 DAY
        SETTINGS index_granularity = 8192;
    """;

    @Language("ClickHouse")
    private static final String SAMPLES_VIEW = """
        CREATE OR REPLACE VIEW @@samples@@ AS
        WITH
            toInt64({ival:Int64} * 1e9) AS interval_ns,

            -- Find first and last absolute bucket numbers
            toUInt64(toUnixTimestamp64Nano(deltaSwitched) / interval_ns) as first_bucket,
            toUInt64(toUnixTimestamp64Nano(lastSwitched) / interval_ns) as last_bucket,

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
                END AS bucket_fraction

        SELECT
            flow.*,

            fromUnixTimestamp64Nano((first_bucket + bucket) * interval_ns) AS timestamp,

            bytes * bucket_fraction / toFloat64(bucket_count) as bytes,
            packets * bucket_fraction / toFloat64(bucket_count) as packets

        FROM @@flows@@ AS flow
        ARRAY JOIN buckets AS bucket
        ORDER BY timestamp;
    """;
}
