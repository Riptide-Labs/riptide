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
                return FlowsSchema.qualifiedRollup(database, "flows_by_application_1m");
            }
            if ("srcAddr".equalsIgnoreCase(groupBy) || "dstAddr".equalsIgnoreCase(groupBy)) {
                return FlowsSchema.qualifiedRollup(database, "flows_by_conversation_1m");
            }
        }
        return FlowsSchema.qualifiedFlows(database);
    }

    /**
     * Resolves target ClickHouse table name for exporter/interface aggregations.
     */
    public static String resolveInterfaceTable(final String database, final int timeRangeMinutes) {
        if (timeRangeMinutes >= ROLLUP_THRESHOLD_MINUTES) {
            return FlowsSchema.qualifiedRollup(database, "flows_by_exporter_iface_1m");
        }
        return FlowsSchema.qualifiedFlows(database);
    }

    /**
     * Resolves target ClickHouse table name for Geo-IP and ASN aggregations.
     */
    public static String resolveGeoAsnTable(final String database, final int timeRangeMinutes) {
        if (timeRangeMinutes >= ROLLUP_THRESHOLD_MINUTES) {
            return FlowsSchema.qualifiedRollup(database, "flows_by_geo_asn_1m");
        }
        return FlowsSchema.qualifiedFlows(database);
    }
}
