/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.mcp.service;

/**
 * Intelligent query router that selects ClickHouse raw tables or 1-minute rollup tables
 * based on query timeframe and aggregation dimensions.
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
     * Resolves target ClickHouse table name for application aggregations.
     */
    public static String resolveApplicationTable(final String database, final int timeRangeMinutes) {
        if (timeRangeMinutes >= ROLLUP_THRESHOLD_MINUTES) {
            return database + ".flows_by_application_1m";
        }
        return database + ".flows";
    }

    /**
     * Resolves target ClickHouse table name for exporter/interface aggregations.
     */
    public static String resolveInterfaceTable(final String database, final int timeRangeMinutes) {
        if (timeRangeMinutes >= ROLLUP_THRESHOLD_MINUTES) {
            return database + ".flows_by_exporter_iface_1m";
        }
        return database + ".flows";
    }

    /**
     * Resolves target ClickHouse table name for Geo-IP and ASN aggregations.
     */
    public static String resolveGeoAsnTable(final String database, final int timeRangeMinutes) {
        if (timeRangeMinutes >= ROLLUP_THRESHOLD_MINUTES) {
            return database + ".flows_by_geo_asn_1m";
        }
        return database + ".flows";
    }
}
