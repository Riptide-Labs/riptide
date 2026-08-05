/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.mcp.service;

import org.riptide.schema.FlowsSchema;

/**
 * Query router that resolves ClickHouse raw tables or 1-minute rollup tables
 * based on query timeframe and aggregation dimensions using {@link FlowsSchema}.
 */
public final class QueryRouter {

    private QueryRouter() {
        // Utility class
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
                return FlowsSchema.qualifiedRollup(database, FlowsSchema.ROLLUP_BY_APPLICATION);
            }
            if ("srcAddr".equalsIgnoreCase(groupBy) || "dstAddr".equalsIgnoreCase(groupBy)) {
                return FlowsSchema.qualifiedRollup(database, FlowsSchema.ROLLUP_BY_CONVERSATION);
            }
            if ("srcAs".equalsIgnoreCase(groupBy) || "dstAs".equalsIgnoreCase(groupBy)
                    || "srcCountry".equalsIgnoreCase(groupBy) || "dstCountry".equalsIgnoreCase(groupBy)) {
                return FlowsSchema.qualifiedRollup(database, FlowsSchema.ROLLUP_BY_GEO_ASN);
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
            return FlowsSchema.qualifiedRollup(database, FlowsSchema.ROLLUP_BY_EXPORTER_IFACE);
        }
        return FlowsSchema.qualifiedFlows(database);
    }

    /**
     * Resolves target ClickHouse table name for Geo-IP and ASN aggregations.
     */
    public static String resolveGeoAsnTable(final String database, final int timeRangeMinutes) {
        if (timeRangeMinutes >= ROLLUP_THRESHOLD_MINUTES) {
            return FlowsSchema.qualifiedRollup(database, FlowsSchema.ROLLUP_BY_GEO_ASN);
        }
        return FlowsSchema.qualifiedFlows(database);
    }
}
