/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.mcp.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.riptide.schema.FlowsSchema;
import org.riptide.schema.RollupAvailability;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class QueryRouterTest {

    /**
     * The verdict is process-wide state set once at startup, so a test that leaves a rollup marked
     * drifted would change every later test's routing.
     */
    @AfterEach
    public void clearDriftVerdict() {
        RollupAvailability.recordDrifted(List.of());
    }

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
        final String asnTable = QueryRouter.resolveTopTalkersTable("riptide", 60, "dstAs");
        final String ifaceTable = QueryRouter.resolveInterfaceTable("riptide", 120);
        final String geoTable = QueryRouter.resolveGeoAsnTable("riptide", 1440);

        assertThat(appTable).isEqualTo("`riptide`.flows_by_application_1m");
        assertThat(convTable).isEqualTo("`riptide`.flows_by_conversation_1m");
        assertThat(asnTable).isEqualTo("`riptide`.flows_by_geo_asn_1m");
        assertThat(ifaceTable).isEqualTo("`riptide`.flows_by_exporter_iface_1m");
        assertThat(geoTable).isEqualTo("`riptide`.flows_by_geo_asn_1m");
    }

    @Test
    public void recognisesRollupTables() {
        assertThat(QueryRouter.isRollup("`riptide`.flows")).isFalse();
        assertThat(QueryRouter.isRollup(null)).isFalse();
        for (final String rollup : FlowsSchema.rollupTableNames()) {
            assertThat(QueryRouter.isRollup(FlowsSchema.qualifiedRollup("riptide", rollup))).isTrue();
        }
    }

    /**
     * The routing consequence of drift detection (#470). Without it, a stale rollup keeps answering
     * every long-range query and detection only writes the wrong answer down in a log.
     */
    @Test
    public void declinesADriftedRollupAndStillRoutesTheOthers() {
        RollupAvailability.recordDrifted(List.of(FlowsSchema.ROLLUP_BY_GEO_ASN));

        assertThat(QueryRouter.resolveGeoAsnTable("riptide", 1440))
                .as("a drifted rollup must be unreachable, not merely logged")
                .isEqualTo("`riptide`.flows");
        assertThat(QueryRouter.resolveTopTalkersTable("riptide", 60, "dstAs")).isEqualTo("`riptide`.flows");

        assertThat(QueryRouter.resolveTopTalkersTable("riptide", 60, "application"))
                .as("one stale rollup must not cost the other three")
                .isEqualTo("`riptide`.flows_by_application_1m");
        assertThat(QueryRouter.resolveTopTalkersTable("riptide", 60, "srcAddr"))
                .isEqualTo("`riptide`.flows_by_conversation_1m");
        assertThat(QueryRouter.resolveInterfaceTable("riptide", 120))
                .isEqualTo("`riptide`.flows_by_exporter_iface_1m");
    }

    /**
     * A rollup row already aggregates a minute of flows, so counting rows there counts partially
     * merged SummingMergeTree parts instead of flows.
     */
    @Test
    public void countsFlowsWithTheSummedMeasureOnRollupsAndRowsOnTheRawTable() {
        assertThat(QueryRouter.flowCountExpression("`riptide`.flows")).isEqualTo("COUNT(*)");
        assertThat(QueryRouter.flowCountExpression("`riptide`.flows_by_conversation_1m"))
                .isEqualTo("SUM(flowCount)");
    }
}
