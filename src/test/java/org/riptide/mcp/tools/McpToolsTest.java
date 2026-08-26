/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.riptide.config.ClickhouseConfig;
import org.riptide.mcp.config.McpProperties;
import org.riptide.mcp.service.RiptideMcpService;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class McpToolsTest {

    private RiptideMcpService mockMcpService;

    @BeforeEach
    public void setUp() {
        final ClickhouseConfig chConfig = new ClickhouseConfig();
        chConfig.setDatabase("riptide_test");
        final McpProperties properties = new McpProperties();
        mockMcpService = new RiptideMcpService(null, chConfig, properties, new ObjectMapper());
    }

    @Test
    public void testsAutoMitigationRulesTool() {
        final AutoMitigationRulesTool tool = new AutoMitigationRulesTool();
        assertThat(tool.getDefinition().getName()).isEqualTo("riptide_generate_mitigation_rules");

        final List<Map<String, Object>> result = tool.execute(Map.of("target_ip", "192.0.2.100", "attack_type", "TCP SYN Flood"));
        assertThat(result).hasSize(1);
        final Map<String, Object> rules = result.get(0);
        assertThat(rules.get("target_ip")).isEqualTo("192.0.2.100");
        assertThat(rules).containsKey("bgp_flowspec");
        assertThat(rules).containsKey("iptables");
        assertThat(rules).containsKey("rtbh_null_route");
    }

    @Test
    public void testsTopTalkersToolValidation() {
        final TopTalkersTool tool = new TopTalkersTool(mockMcpService);
        assertThat(tool.getDefinition().getName()).isEqualTo("riptide_get_top_talkers");

        final List<Map<String, Object>> invalidResult = tool.execute(Map.of("group_by", "invalidDimension"));
        assertThat(invalidResult.get(0)).containsKey("error");
    }

    @Test
    public void testsHostTraceToolValidation() {
        final HostTraceTool tool = new HostTraceTool(mockMcpService);
        assertThat(tool.getDefinition().getName()).isEqualTo("riptide_trace_host_flow");

        final List<Map<String, Object>> missingIp = tool.execute(Map.of());
        assertThat(missingIp.get(0)).containsKey("error");

        final List<Map<String, Object>> invalidIp = tool.execute(Map.of("ip_address", "not-an-ip"));
        assertThat(invalidIp.get(0)).containsKey("error");
    }

    @Test
    public void testsInterfaceUtilizationToolDefinition() {
        final InterfaceUtilizationTool tool = new InterfaceUtilizationTool(mockMcpService);
        assertThat(tool.getDefinition().getName()).isEqualTo("riptide_get_interface_utilization");
    }

    @Test
    public void testsGeoAsnToolDefinition() {
        final GeoAsnTool tool = new GeoAsnTool(mockMcpService);
        assertThat(tool.getDefinition().getName()).isEqualTo("riptide_get_geo_asn_distribution");
    }

    @Test
    public void testsTrafficSpikesToolDefinition() {
        final TrafficSpikesTool tool = new TrafficSpikesTool(mockMcpService);
        assertThat(tool.getDefinition().getName()).isEqualTo("riptide_detect_traffic_spikes");
    }

    /**
     * On the rollup route a row is a minute's worth of pre-aggregated flows, so the flow count has
     * to come from the summed measure. COUNT(*) there would count SummingMergeTree parts, which
     * undercounts and shifts as merges run — during a volumetric attack, the case this tool exists
     * for.
     */
    @Test
    public void countsFlowsWithTheSummedMeasureOnTheRollupRoute() {
        final RecordingMcpService recording = new RecordingMcpService();
        final TrafficSpikesTool tool = new TrafficSpikesTool(recording);

        tool.execute(Map.of("time_range_minutes", 1440));

        assertThat(recording.lastSql).contains("`riptide_test`.flows_by_conversation_1m");
        assertThat(recording.lastSql).contains("SUM(flowCount) AS flow_count");
        assertThat(recording.lastSql).doesNotContain("COUNT(*)");
    }

    @Test
    public void countsFlowRowsOnTheRawRoute() {
        final RecordingMcpService recording = new RecordingMcpService();
        final TrafficSpikesTool tool = new TrafficSpikesTool(recording);

        tool.execute(Map.of("time_range_minutes", 15));

        assertThat(recording.lastSql).contains("`riptide_test`.flows ");
        assertThat(recording.lastSql).contains("COUNT(*) AS flow_count");
    }

    /**
     * Every tool that routes to a table goes through the coverage-reporting entry point (#609).
     *
     * <p>Driven through the tools rather than through {@code RiptideMcpService}, because a test on
     * the service passes while a tool that never calls it stays broken — and the issue named only
     * two of the four routing tools, so the set had to be found rather than taken.</p>
     */
    @Test
    public void everyRoutingToolReportsCoverage() {
        final var byTool = new java.util.LinkedHashMap<String, RecordingMcpService>();

        record Routing(String name, McpTool tool, RecordingMcpService service) { }
        final List<Routing> routing = new java.util.ArrayList<>();
        for (final String name : List.of("topTalkers", "trafficSpikes", "geoAsn", "interfaceUtilization")) {
            final var recording = new RecordingMcpService();
            byTool.put(name, recording);
            routing.add(new Routing(name, switch (name) {
                case "topTalkers" -> new TopTalkersTool(recording);
                case "trafficSpikes" -> new TrafficSpikesTool(recording);
                case "geoAsn" -> new GeoAsnTool(recording);
                default -> new InterfaceUtilizationTool(recording);
            }, recording));
        }

        for (final Routing entry : routing) {
            entry.tool().execute(Map.of("time_range_minutes", 1440));
            assertThat(entry.service().lastTable)
                    .as("%s must route through executeRangeQuery, or its answers never say they are"
                            + " short", entry.name())
                    .isNotNull();
            assertThat(entry.service().lastSql)
                    .as("%s must still pass its own SQL, not the coverage probe", entry.name())
                    .contains(entry.service().lastTable);
        }
        assertThat(byTool).hasSize(4);
    }

    /** Captures the SQL a tool builds instead of running it. */
    private static final class RecordingMcpService extends RiptideMcpService {
        private String lastSql;
        private String lastTable;

        private RecordingMcpService() {
            super(null, databaseNamed("riptide_test"), new McpProperties(), new ObjectMapper());
        }

        private static ClickhouseConfig databaseNamed(final String database) {
            final ClickhouseConfig config = new ClickhouseConfig();
            config.setDatabase(database);
            return config;
        }

        @Override
        public List<Map<String, Object>> executeQuery(final String sqlQuery) {
            this.lastSql = sqlQuery;
            return List.of();
        }

        /**
         * Recorded at this seam, not at {@code executeQuery}, because a routed tool now makes two
         * round trips: its own query and the coverage probe behind it. Capturing the last
         * {@code executeQuery} would record the probe and assert nothing about the tool.
         */
        @Override
        public List<Map<String, Object>> executeRangeQuery(final String sqlQuery, final String table,
                                                            final int timeRangeMinutes) {
            this.lastSql = sqlQuery;
            this.lastTable = table;
            return List.of();
        }
    }
}
