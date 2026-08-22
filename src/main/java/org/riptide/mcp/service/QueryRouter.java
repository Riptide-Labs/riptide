/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.mcp.service;

import lombok.extern.slf4j.Slf4j;
import org.riptide.schema.FlowsSchema;
import org.riptide.schema.RollupAvailability;

/**
 * Query router that resolves ClickHouse raw tables or 1-minute rollup tables
 * based on query timeframe and aggregation dimensions using {@link FlowsSchema}.
 *
 * <p>A rollup whose shape drifted from what this version intends is declined here and the query
 * falls back to raw {@code flows} (#470). Detection alone would only record the wrong answer in a
 * log while continuing to serve it.</p>
 */
@Slf4j
public final class QueryRouter {

    private QueryRouter() {
        // Utility class
    }

    /**
     * The rollup if it is usable, otherwise raw {@code flows}.
     *
     * <p>Logged at the point of fallback rather than only at startup detection: an operator looking
     * at a query that suddenly got slower needs the reason where they are already looking. Logged
     * <em>once per rollup</em>, because this sits on the query path — a dashboard-driven deployment
     * would otherwise emit a WARN per query, indefinitely, for a condition already reported at
     * startup.</p>
     *
     * <p><b>The fallback is not free, and the message says so.</b> Raw {@code flows} is retained for
     * {@link FlowsSchema#DEFAULT_TTL_DAYS} days against the rollups'
     * {@link FlowsSchema#DEFAULT_ROLLUP_TTL_DAYS} — the rollups exist partly so long-range queries
     * outlive the raw table's expiry. A query reaching past raw retention therefore comes back
     * <em>truncated</em>, not merely slower, and silently so unless the operator is told.</p>
     */
    private static String rollupOrFlows(final String database, final String rollup) {
        final String table = FlowsSchema.qualifiedRollup(database, rollup);
        if (RollupAvailability.usable(table)) {
            return table;
        }
        if (RollupAvailability.firstRefusalOf(rollup)) {
            log.warn("Rollup {} is unusable (see the startup log for what differs); answering from raw"
                    + " flows instead. Queries are slower, and any range older than {} days — the raw"
                    + " retention, against the rollups' {} — comes back incomplete.",
                    rollup, FlowsSchema.DEFAULT_TTL_DAYS, FlowsSchema.DEFAULT_ROLLUP_TTL_DAYS);
        }
        return FlowsSchema.qualifiedFlows(database);
    }

    /**
     * Threshold in minutes above which queries are routed to 1-minute rollup tables.
     */
    public static final int ROLLUP_THRESHOLD_MINUTES = 60;

    /**
     * Resolves target ClickHouse table name for top talker queries based on dimension and time range.
     */
    public static String resolveTopTalkersTable(final String database, final int timeRangeMinutes, final String groupBy) {
        if (timeRangeMinutes >= ROLLUP_THRESHOLD_MINUTES) {
            if ("application".equalsIgnoreCase(groupBy) || "protocol".equalsIgnoreCase(groupBy)) {
                return rollupOrFlows(database, FlowsSchema.ROLLUP_BY_APPLICATION);
            }
            if ("srcAddr".equalsIgnoreCase(groupBy) || "dstAddr".equalsIgnoreCase(groupBy)) {
                return rollupOrFlows(database, FlowsSchema.ROLLUP_BY_CONVERSATION);
            }
            if ("srcAs".equalsIgnoreCase(groupBy) || "dstAs".equalsIgnoreCase(groupBy)
                    || "srcCountry".equalsIgnoreCase(groupBy) || "dstCountry".equalsIgnoreCase(groupBy)) {
                return rollupOrFlows(database, FlowsSchema.ROLLUP_BY_GEO_ASN);
            }
        }
        return FlowsSchema.qualifiedFlows(database);
    }

    /**
     * Whether a resolved table is one of the rollups rather than the raw {@code flows} table.
     * Derived from {@link FlowsSchema#rollupTableNames()} so a rollup added to the schema is
     * classified here without a second edit.
     */
    public static boolean isRollup(final String table) {
        return table != null && FlowsSchema.rollupTableNames().stream()
                .anyMatch(rollup -> table.endsWith("." + rollup));
    }

    /**
     * The SQL expression for a flow count against a resolved table. A rollup row is already an
     * aggregate of a minute's flows carrying its own {@code flowCount} measure, so counting rows
     * there would count partially merged {@code SummingMergeTree} parts — a number that undercounts
     * and shifts as merges run in the background.
     */
    public static String flowCountExpression(final String table) {
        return isRollup(table) ? "SUM(flowCount)" : "COUNT(*)";
    }

    /**
     * Resolves target ClickHouse table name for exporter/interface aggregations.
     */
    public static String resolveInterfaceTable(final String database, final int timeRangeMinutes) {
        if (timeRangeMinutes >= ROLLUP_THRESHOLD_MINUTES) {
            return rollupOrFlows(database, FlowsSchema.ROLLUP_BY_EXPORTER_IFACE);
        }
        return FlowsSchema.qualifiedFlows(database);
    }

    /**
     * Resolves target ClickHouse table name for Geo-IP and ASN aggregations.
     */
    public static String resolveGeoAsnTable(final String database, final int timeRangeMinutes) {
        if (timeRangeMinutes >= ROLLUP_THRESHOLD_MINUTES) {
            return rollupOrFlows(database, FlowsSchema.ROLLUP_BY_GEO_ASN);
        }
        return FlowsSchema.qualifiedFlows(database);
    }
}
