/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.mcp.tools;

import org.riptide.mcp.config.ConditionalOnMcpEnabled;
import org.riptide.mcp.protocol.McpToolDefinition;
import org.riptide.mcp.service.QueryRouter;
import org.riptide.mcp.service.RiptideMcpService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * MCP tool for detecting PPS and BPS traffic spikes across target victim IPs.
 */
@ConditionalOnMcpEnabled
@Component
public class TrafficSpikesTool implements McpTool {

    private final RiptideMcpService mcpService;

    public TrafficSpikesTool(final RiptideMcpService mcpService) {
        this.mcpService = mcpService;
    }

    @Override
    public McpToolDefinition getDefinition() {
        return McpToolDefinition.builder()
                .name("riptide_detect_traffic_spikes")
                .description("Detects PPS and BPS volumetric spikes and anomalous target destination IPs.")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "time_range_minutes", Map.of("type", "integer", "description", "Time range in minutes")
                        ),
                        "required", List.of("time_range_minutes")
                ))
                .build();
    }

    @Override
    public List<Map<String, Object>> execute(final Map<String, Object> params) {
        final int requestedRange =
                ToolParams.requestedTimeRangeMinutes(ToolParams.safe(params).get("time_range_minutes"), 15);
        final int timeRange = ToolParams.timeRangeMinutes(ToolParams.safe(params).get("time_range_minutes"), 15);
        final String db = mcpService.getDatabaseName();
        final String table = QueryRouter.resolveTopTalkersTable(db, timeRange, "dstAddr");

        final String sql = String.format(
                "SELECT dstAddr, SUM(packets) AS total_packets, SUM(bytes) AS total_bytes, %s AS flow_count FROM %s WHERE timestamp >= now() - INTERVAL %d MINUTE GROUP BY dstAddr ORDER BY total_packets DESC LIMIT 20",
                QueryRouter.flowCountExpression(table), table, timeRange
        );

        return mcpService.executeRangeQuery(sql, table, timeRange, requestedRange);
    }
}
