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
        final String appTable = QueryRouter.resolveApplicationTable("riptide", 15);
        final String ifaceTable = QueryRouter.resolveInterfaceTable("riptide", 30);
        final String geoTable = QueryRouter.resolveGeoAsnTable("riptide", 45);

        assertThat(appTable).isEqualTo("riptide.flows");
        assertThat(ifaceTable).isEqualTo("riptide.flows");
        assertThat(geoTable).isEqualTo("riptide.flows");
    }

    @Test
    public void routesLongLookbackToOneMinuteRollupTables() {
        final String appTable = QueryRouter.resolveApplicationTable("riptide", 60);
        final String ifaceTable = QueryRouter.resolveInterfaceTable("riptide", 120);
        final String geoTable = QueryRouter.resolveGeoAsnTable("riptide", 1440);

        assertThat(appTable).isEqualTo("riptide.flows_by_application_1m");
        assertThat(ifaceTable).isEqualTo("riptide.flows_by_exporter_iface_1m");
        assertThat(geoTable).isEqualTo("riptide.flows_by_geo_asn_1m");
    }
}
