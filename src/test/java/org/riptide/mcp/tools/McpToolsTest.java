/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.mcp.tools;

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
        mockMcpService = new RiptideMcpService(null, chConfig, properties);
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
}
