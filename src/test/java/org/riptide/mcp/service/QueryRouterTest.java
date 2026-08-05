/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.mcp.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class QueryRouterTest {

    @Test
    public void routesShortLookbackToRawFlowsTable() {
        final String appTable = QueryRouter.resolveTopTalkersTable("riptide", 15, "application");
        final String ifaceTable = QueryRouter.resolveInterfaceTable("riptide", 30);
        final String geoTable = QueryRouter.resolveGeoAsnTable("riptide", 45);

        assertThat(appTable).isEqualTo("`riptide`.flows");
        assertThat(ifaceTable).isEqualTo("`riptide`.flows");
        assertThat(geoTable).isEqualTo("`riptide`.flows");
    }

    @Test
    public void routesLongLookbackToOneMinuteRollupTables() {
        final String appTable = QueryRouter.resolveTopTalkersTable("riptide", 60, "application");
        final String convTable = QueryRouter.resolveTopTalkersTable("riptide", 60, "srcAddr");
        final String ifaceTable = QueryRouter.resolveInterfaceTable("riptide", 120);
        final String geoTable = QueryRouter.resolveGeoAsnTable("riptide", 1440);

        assertThat(appTable).isEqualTo("`riptide`.flows_by_application_1m");
        assertThat(convTable).isEqualTo("`riptide`.flows_by_conversation_1m");
        assertThat(ifaceTable).isEqualTo("`riptide`.flows_by_exporter_iface_1m");
        assertThat(geoTable).isEqualTo("`riptide`.flows_by_geo_asn_1m");
    }
}
