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
 * MCP tool for querying interface bandwidth utilization and SNMP interface speeds.
 */
@ConditionalOnMcpEnabled
@Component
public class InterfaceUtilizationTool implements McpTool {

    private final RiptideMcpService mcpService;

    public InterfaceUtilizationTool(final RiptideMcpService mcpService) {
        this.mcpService = mcpService;
    }

    @Override
    public McpToolDefinition getDefinition() {
        return McpToolDefinition.builder()
                .name("riptide_get_interface_utilization")
                .description("Queries network exporter interface bandwidth utilization and SNMP interface speeds.")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "time_range_minutes", Map.of("type", "integer", "description", "Time range in minutes"),
                                "limit", Map.of("type", "integer", "description", "Max records to return")
                        ),
                        "required", List.of("time_range_minutes")
                ))
                .build();
    }

    @Override
    public List<Map<String, Object>> execute(final Map<String, Object> params) {
        final Map<String, Object> safeParams = ToolParams.safe(params);
        final int timeRange = ToolParams.timeRangeMinutes(safeParams.get("time_range_minutes"), 15);
        final int limit = ToolParams.limit(safeParams.get("limit"), 20);
        final String db = mcpService.getDatabaseName();

        final String table = QueryRouter.resolveInterfaceTable(db, timeRange);
        final String sql = String.format(
                "SELECT exporterAddr, exporterName, inputSnmp, outputSnmp, SUM(bytes) AS total_bytes FROM %s WHERE timestamp >= now() - INTERVAL %d MINUTE GROUP BY exporterAddr, exporterName, inputSnmp, outputSnmp ORDER BY total_bytes DESC LIMIT %d",
                table, timeRange, limit
        );

        return mcpService.executeQuery(sql);
    }
}
