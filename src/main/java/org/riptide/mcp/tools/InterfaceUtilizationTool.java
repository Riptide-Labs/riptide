/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.mcp.tools;

import org.riptide.mcp.protocol.McpToolDefinition;
import org.riptide.mcp.service.QueryRouter;
import org.riptide.mcp.service.RiptideMcpService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * MCP tool for querying interface bandwidth utilization and SNMP interface speeds.
 */
@Component
public class InterfaceUtilizationTool {

    private final RiptideMcpService mcpService;

    public InterfaceUtilizationTool(final RiptideMcpService mcpService) {
        this.mcpService = mcpService;
    }

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

    public List<Map<String, Object>> execute(final Map<String, Object> params) {
        final int timeRange = Math.min(Math.max(1, ((Number) params.getOrDefault("time_range_minutes", 15)).intValue()), 43200);
        final int limit = Math.min(Math.max(1, ((Number) params.getOrDefault("limit", 20)).intValue()), 500);
        final String db = mcpService.getDatabaseName();

        final String table = QueryRouter.resolveInterfaceTable(db, timeRange);
        final String sql = String.format(
                "SELECT exporterAddr, exporterName, inputSnmp, outputSnmp, SUM(bytes) AS total_bytes FROM %s WHERE timestamp >= now() - INTERVAL %d MINUTE GROUP BY exporterAddr, exporterName, inputSnmp, outputSnmp ORDER BY total_bytes DESC LIMIT %d",
                table, timeRange, limit
        );

        return mcpService.executeQuery(sql);
    }
}
